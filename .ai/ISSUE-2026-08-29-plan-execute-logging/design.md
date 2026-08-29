# Plan-and-Execute 结构化日志设计

## 数据流

```text
HTTP 请求 -> ChatService 阶段日志 -> 追踪模型装饰器 -> Planner / 直接 Tool Use / 最终回答
                    |                         |
                    v                         v
             RAG 与计划日志             模型请求、响应、tool call
                    |
                    v
            WorkflowRunner -> StateGraph 节点、路由与循环日志
                    |
                    v
             工具执行器 -> 工具参数、输出、耗时与异常
```

## 组件职责

`ChatService` 在请求入口创建 `traceId`，并在该请求范围内放入 MDC。它记录请求阶段、RAG、Planner、计划确认、工作流摘要和最终回答。`traceId` 通过 `ExecutionState` 传入图执行路径，确保跨组件关联。

`WorkflowRunner` 和 `StockAnalysisWorkflow` 记录开始、恢复、节点进入、Reflector 决策、Critic 路由、重试与结束事件。因此能明确看到 `RETRY`/`ADD_NEWS` 回到 `INIT` 的循环和任务尝试次数。

`StockAnalysisTaskNode` 作为工作流工具调用的唯一入口，记录工具名称、参数、完整 `ToolResult` 和耗时。直接 LangChain4j Tool Use 仍由其工具方法执行，模型选择工具的原始请求由模型装饰器记录。

模型装饰器包装项目的 `ChatLanguageModel`。每次调用均记录模型输入消息、模型响应、返回的 tool execution requests 和执行结果消息，以覆盖 Planner、最终答案生成以及 LangChain4j 内部的连续工具调用。

## 日志与隐私

所有事件采用参数化 SLF4J 日志，并写入 `traceId`、`sessionId`、`executionId` 和阶段名。用户原文、RAG 内容、模型提示词、工具参数和工具结果默认完整记录，符合本次诊断要求。新增配置限制每条记录的最大字符数；日志中标记截断状态。此能力应仅在受控诊断环境启用或通过日志保留策略保护。

## 测试

为模型装饰器、工具节点和工作流路由日志提供单元测试，覆盖模型 tool call、完整工具输出、`RETRY` 路由和日志截断。保留既有 ChatService 与工作流测试作为回归覆盖。
