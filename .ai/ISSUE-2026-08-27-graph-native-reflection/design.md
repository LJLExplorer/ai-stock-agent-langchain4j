# 图内反思工作流设计

## 架构

`ChatService` 仍在图外完成会话、记忆、RAG 和 Planner/Validator。通过校验后创建 `ExecutionState` 并调用 `WorkflowRunner.run(state)`；Runner 只编译/调用图和加载恢复状态，不能再自行调用 Reflector 或循环重试。

图状态继续只保存可序列化上下文，`ExecutionState` 由节点闭包持有并通过 `ExecutionStateStore` 作为 Checkpoint 持久化。节点对该状态的变更是唯一业务事实来源。

```text
INIT -> EXECUTE_TASKS -> REFLECTOR -> CRITIC
                                     |       |
                                     |       +-> ANSWER -> END
                                     |       +-> RETRY -> EXECUTE_TASKS
                                     |       +-> ADD_NEWS -> EXECUTE_TASKS
                                     |       +-> FAILED -> END
```

初始任务节点仍以 fan-out 方式表示；汇合至 `REFLECTOR`。补充新闻和重试走统一执行阶段，任务节点对已完成任务保持幂等。

## 组件职责

- `WorkflowReflector`：复盘任务结果，产生重试任务和补充新闻任务建议。
- `WorkflowCritic`：将反思建议转为受限图路由。通过仅在没有重试、没有补充任务、没有终态失败时成立。
- `StockAnalysisWorkflow`：注册真实节点动作和条件边，节点执行后保存状态。
- `WorkflowRunner`：启动或恢复 StateGraph，返回图完成后的 `ExecutionState`。
- `AnswerGenerator`：以现有无工具 `StockAnalysisAssistant` 为依赖，接收问题、RAG 上下文和可信任务结果，生成并写入 `finalAnswer`。

## 错误与状态

任何节点异常都转换为带原因的失败状态并保存。生成答案失败进入 `FAILED`，不会再回退为工具调用。`ChatService` 在工作流完成时读取 `finalAnswer`；失败时将状态中的任务结果和错误交给既有降级表达逻辑。

## 测试

测试条件边的四个分支、新闻补齐、有限重试、答案只由无工具 Generator 创建、执行状态持久化以及 ChatService 不再进行图外答案生成。保留 Planner、RAG 和 ToolInvocation 兼容测试。
