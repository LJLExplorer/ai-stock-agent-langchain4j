# Plan-and-Execute 结构化日志设计

## 数据流

```text
Planner -> PlanValidator -> ChatService 计划确认日志
                              |
                              v
                        WorkflowRunner 开始/恢复日志
                              |
                              v
                        WorkflowRunner 结束日志
                              |
                              v
                  ChatService 执行汇总日志与最终回答
```

## 组件职责

`ChatService` 在创建 `ExecutionState` 后记录计划确认事件，字段为 `sessionId`、`executionId`、`symbol` 和任务名称；在工作流返回后记录执行摘要，字段为状态、总耗时、成功/失败数量和失败任务错误摘要。

`WorkflowRunner` 记录执行开始、恢复、完成和失败事件，因此直接调用 `resume` 的场景也有可追踪日志。它只读取 `ExecutionState` 中的状态字段与时间戳，不序列化任务结果。

## 日志与隐私

所有事件采用参数化 SLF4J 日志。用户原文、RAG 内容、工具参数、工具结果正文不进入新增日志。任务错误信息限制为短摘要，避免异常中携带大段外部响应时造成日志膨胀。

## 测试

为日志摘要提取逻辑提供单元测试，覆盖完成和失败任务的统计、耗时计算及错误摘要截断；保留既有 ChatService 与工作流测试作为回归覆盖。
