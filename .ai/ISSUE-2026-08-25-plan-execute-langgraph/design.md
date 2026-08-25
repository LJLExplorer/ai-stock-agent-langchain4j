# Plan-and-Execute 工作流设计

## 总体架构

请求首先进入现有 Planner 和 `PlanValidator`。校验后的 `AgentPlan` 被转换成带任务依赖的 LangGraph4j StateGraph。图状态包含 executionId、sessionId、原始问题、标准化计划、任务状态、工具结果、重试次数、动态任务和最终答案。

工作流由 Graph 节点驱动：初始化节点创建或恢复执行状态；并行任务节点执行行情、技术、财务和新闻工具；Reflector 节点校验结果；Retry 节点处理有限重试；DynamicTask 节点追加白名单内的补充任务；Answer 节点生成最终答案；完成或失败节点持久化终态。

LangGraph4j 负责图的节点、边、循环和状态传递。MongoDB Checkpoint 由项目自定义适配层负责，避免依赖框架中不确定的存储实现。若 LangGraph4j 的具体版本不支持直接表达并行分支，则使用图的 fan-out/fan-in 语义结合受控线程池实现，所有结果仍回写统一 State。

## 状态模型

`ExecutionState`：

- `executionId`：全局执行标识，也是恢复键。
- `sessionId`、`userId`、`originalQuestion`。
- `plan`：标准化后的 AgentPlan。
- `tasks`：任务列表及状态、依赖、结果摘要、attempts、错误信息。
- `workflowStatus`：`PLANNED`、`RUNNING`、`PAUSED`、`RETRYING`、`COMPLETED`、`FAILED`。
- `currentNode`、`version`、`createdAt`、`updatedAt`。

MongoDB 集合使用 `agent_execution_states`。每次节点完成后通过版本条件更新保存 Checkpoint；版本冲突时重新读取最新状态，防止并发恢复覆盖结果。

## 任务图

```text
INIT/RESUME
    ↓
FAN-OUT ── MARKET_DATA ─────┐
         ├ TECHNICAL ────────┤
         ├ FINANCIAL ────────┤ → REFLECTOR → RETRY / DYNAMIC_TASK / ANSWER
         └ NEWS ──────────────┘
```

任务节点只执行由 `StockAnalysisTask` 映射出的工具，不能接受 Planner 任意输出的工具名。任务开始前检查幂等状态；任务已成功则直接复用结果。

## Reflector 规则

第一版使用确定性规则优先，避免让模型自由决定是否通过：

1. 结果非空且没有工具错误。
2. 返回标的与计划标的一致。
3. 需要时间范围的结果包含可识别时间信息。
4. 结果满足任务最低内容要求。

失败时，若 `attempts < maxAttempts`，进入 Retry 节点；若结果存在明确缺口且对应任务尚未执行，则追加白名单任务。超过限制进入 FAILED。Reflector 的判断、原因和决策写入任务轨迹。

## 生成答案与兼容性

Answer 节点复用现有 `StockAnalysisAssistant` 的回答规范，但输入改为经过 Reflector 标记可信的工具结果。ChatService 继续负责会话、RAG、业务消息和响应兼容；响应中的 ToolInvocation 从执行状态和现有 ChatMemory 合并生成。

## 错误处理与测试

- Planner/Validator 失败：不创建执行图，沿用现有降级路径。
- 节点异常：记录任务错误并进入 Retry 或 FAILED。
- MongoDB 暂时不可用：不标记任务完成，返回可恢复错误。
- 恢复时：按版本加载状态，跳过成功任务，继续未完成节点。
- 测试覆盖状态迁移、Mongo Checkpoint、并行汇合、幂等恢复、Reflector 重试/补充任务、失败终态和 ChatService 回归。
