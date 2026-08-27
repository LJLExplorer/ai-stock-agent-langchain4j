# 图内反思工作流需求

## 背景

现有股票分析工作流已用 LangGraph4j 声明节点和扇入扇出边，但 Reflector 的决策、重试循环和最终答案生成仍在图外完成；图中的 `REFLECTOR` 与 `ANSWER` 节点只是占位状态更新。

## 目标

将股票分析收敛为由 StateGraph 驱动的可恢复工作流：Planner 规划，Executor 执行白名单任务，Reflector 复盘，Critic 作出通过/重试/补任务/失败裁决，Generator 只基于已验收资料生成答案。

## 功能需求

1. 图内必须存在可执行的 `INIT`、任务执行、`REFLECTOR`、`CRITIC`、`RETRY`、`ANSWER` 和失败终止路径。
2. Reflector 必须保留新闻补齐：初始计划未含新闻任务且现有任务无须重试时，追加一次 `NEWS_ANALYSIS`。
3. Critic 依据确定性规则决定通过、重试、补任务或失败，不由 LLM 决定工具结果是否可信。
4. `ANSWER` 节点将可信任务结果交给现有无工具助手，写入 `ExecutionState.finalAnswer`；不得再次注册或调用业务工具。
5. 每次节点改变执行状态后保存 Checkpoint；恢复执行时已完成任务不可重复执行。
6. 删除图外反思循环、图内占位 Reflector/Answer 实现，以及不再使用的无状态图运行入口。
7. ChatService 直接使用工作流写入的最终答案，保留会话、RAG、消息记录、工具调用展示及 Planner 降级行为。
8. README 描述实际图内分层和条件路由，不再把图外循环误称为图内反思。

## 验收标准

- 缺少新闻任务的有效计划会在图内补齐并重新执行新闻节点。
- 不可信任务在图内进入有限重试，超过上限进入失败终态。
- 全部可信结果仅经无工具 Generator 产出并持久化最终答案。
- 恢复执行不重复已完成任务。
- 定向测试、完整 Maven 测试和 `git diff --check` 通过。
