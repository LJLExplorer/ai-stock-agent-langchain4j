# 运行日志可靠性修复设计

## 1. 方案

采用已确认的“针对性可靠性修复”方案：修复确定性的异步/SSE 缺陷，对明确股票代码采用本地白名单规划，并增强 Judge 降级诊断。外部 Milvus 和模型网络问题保持可见，不通过降日志级别伪装为成功。

## 2. 异步状态时序

新的启动顺序为：

```text
创建或确认会话
  -> 生成 executionId
  -> 保存 PLANNED 占位状态
  -> 提交有界后台任务
  -> 发布 accepted 事件
  -> 返回 202
  -> 后台规划并构造完整状态
  -> WorkflowRunner 乐观锁替换占位状态
  -> 执行节点 Checkpoint
```

占位状态沿用 `ExecutionState`，不增加第二套执行句柄集合。这样状态查询和所有权校验继续以 MongoDB 为唯一事实来源，也符合既有设计中“先创建 PLANNED 状态，再返回 202”的约束。

`WorkflowRunner.run` 在首次保存前读取同 ID 状态：不存在时执行原有 `insert`；存在时只接受严格占位状态，复制其版本并按版本条件替换。所有者、会话、状态、plan/tasks 均参与占位校验，防止覆盖真实 Checkpoint。

## 3. SSE 异常处理

`GlobalExceptionHandler` 增加 `ResponseStatusException` 专用处理器，返回原状态和空 body。因为没有 JSON body，Spring 无需为 `text/event-stream` 请求查找 JSON converter，原始 404 可以干净结束。

所有权策略保持不变：不存在和非所有者都返回 404。

## 4. Planner 快速路径

`ChatService.planForExecution` 先检查用户原文能否由 `PlannerTextParser` 解析出明确股票代码及任务。解析结果仍进入 `PlanValidator`；验证成功就直接执行，不调用模型。解析失败或验证失败时继续现有模型 Planner 与文本兜底流程。

该方案复用现有受限解析器和白名单，不引入新的自由文本执行能力。它直接覆盖日志中的“600519 + 技术分析”请求，并减少一次约 25 秒的无效模型调用。

## 5. Judge 诊断

保持 Judge 单次调用和确定性降级。解析过程把不同失败阶段映射为稳定原因：

- `JUDGE_EMPTY_RESPONSE`
- `JUDGE_MISSING_JSON_OBJECT`
- `JUDGE_INVALID_JSON`
- `JUDGE_INVALID_RATING`
- `JUDGE_INVALID_CONFIDENCE`
- `JUDGE_INVALID_DATA_AS_OF`
- `DATE_AFTER_DATA_AS_OF`
- `UNKNOWN_EVIDENCE_ID`

日志增加响应长度，仍不记录原始模型内容。第三方模型不遵守协议时结果仍为 `INSUFFICIENT_DATA`，不会让未验证结论进入最终答案。

## 6. 外部依赖边界

Milvus 连接失败发生在依赖未就绪阶段，随后服务启动并成功完成 Hybrid Search。将 Milvus 改为可选依赖会改变知识库、长期记忆和 RAG 多个组件的语义，本次不实施。

LLM 请求 180 秒超时来自外部 MaaS 接口，LangChain4j 内部重试后成功。本次不统一缩短共享模型超时，避免让耗时较长的深度角色更易失败。最终报告会明确这两项需要通过服务编排、健康检查、网络或模型供应商配置处理。

## 7. 测试策略

- `ResearchExecutionServiceTest`：后台未开始时占位状态已可读取；队列拒绝后状态失败。
- `WorkflowRunnerTest`：合法占位状态被替换；非占位状态拒绝覆盖；同步首次插入保持不变。
- `ResearchExecutionControllerTest`：SSE 缺失 execution 在全局异常处理器参与时返回干净 404。
- `ChatServicePlannerTest`：明确代码走本地计划且 Planner 零调用；无法本地解析时仍调用 Planner。
- `DeepResearchServiceTest`：Judge 空响应、无 JSON、非法字段产生对应降级原因。
- 最后执行完整 Maven 测试、前端测试与生产构建。
