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

异步入口与 `ChatService` 共用同一个确定性执行问题构造函数：仅当消息尚未包含 `orderId` 对应股票代码时追加上下文。这样占位记录与完整执行状态的 `originalQuestion` 始终一致，同时避免前端已拼接股票代码后端又重复追加。

## 3. SSE 异常处理

`GlobalExceptionHandler` 增加 `ResponseStatusException` 专用处理器，返回原状态和空 body。因为没有 JSON body，Spring 无需为 `text/event-stream` 请求查找 JSON converter，原始 404 可以干净结束。

所有权策略保持不变：不存在和非所有者都返回 404。

## 4. Planner 快速路径

`ChatService.resolveRetrievalQuery` 先检查用户原文是否已有明确六位股票代码；命中时保留原问题并本地确定话题边界，不调用 Query Rewrite。`planForExecution` 再由 `PlannerTextParser` 解析股票代码及任务。解析结果仍进入 `PlanValidator`；验证成功就直接执行，不调用 Planner。解析失败或验证失败时继续现有模型 Planner 与文本兜底流程。

该方案复用现有受限解析器和白名单，不引入新的自由文本执行能力。它直接覆盖日志中的“600519 + 技术分析”请求，并消除 Query Rewrite 与 Planner 两次不必要模型调用。

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

## 7. 证据范围审议与真实进度

异步接收使用独立的 `EXECUTION_ACCEPTED` 事件；只有工作流实际进入深度研究节点才发送 `DEEP_RESEARCH_STARTED`。领域专家由 EvidencePack 类型决定：FINANCIAL 对应 FUNDAMENTAL，TECHNICAL/MARKET 对应 TECHNICAL，NEWS 对应 NEWS；BULL、BEAR、RISK 始终保留。独立专家通过专用虚拟线程执行器并行运行且不互相读取未完成输出，全部结束后按固定角色顺序组装摘要，Judge 最后单次裁决。

`DEEP_RESEARCH_STARTED` 携带实际 `roleCount`，前端总步数为“计划 1 + 已规划工具任务数 + 证据包 1 + 实际角色/Judge 数 + 答案 1”。工具和角色按 node 去重，SSE 重放不会重复计数；`WORKFLOW_COMPLETED` 才能把进度置为 100%。界面的秒数只展示真实墙钟耗时，不驱动百分比，同时展示当前并行活动角色。

模型调用正文继续由 `TracingChatLanguageModel` 输出 `<redacted>`。角色边界和耗时改由 `deep_research_role_started/finished` 日志表达，既能定位慢角色，也不泄漏用户问题、RAG 内容或模型响应。所有角色上下文前置权威分析日期、数据截止日与实际证据类型，明确不晚于分析日期的数据不是未来数据；风险字段只允许描述标的投资风险，不接受角色质量或流水线问题。

## 8. 可点击证据

工作流 EvidencePack 的有效 `FinancialFact` 映射到既有 `KnowledgeSource` 结构：`documentId` 保存 evidenceId，`documentType=EVIDENCE`，其余字段保存来源名、指标摘要、时点和 URL。技术分析补充腾讯行情原文地址。ChatResponse 和持久化助手消息都携带这些来源。

前端把裸 `[evidence:ev-...]` 转成“证据：来源名”的 Markdown 链接；有可信 HTTP(S) URL 时打开原文，否则定位到右侧来源项。来源 URL 只接受 HTTP(S)，避免把不受控协议写入链接。

## 9. 测试策略

- `ResearchExecutionServiceTest`：后台未开始时占位状态已可读取；队列拒绝后状态失败。
- `WorkflowRunnerTest`：合法占位状态被替换；非占位状态拒绝覆盖；同步首次插入保持不变。
- `ResearchExecutionControllerTest`：SSE 缺失 execution 在全局异常处理器参与时返回干净 404。
- `ChatServicePlannerTest`：明确代码走本地计划且 Planner 零调用；无法本地解析时仍调用 Planner。
- `ChatServiceQueryRewriteTest`：明确代码走本地查询解析且 Query Rewrite 零调用。
- `DeepResearchServiceTest`：Judge 空响应、无 JSON、非法字段产生对应降级原因。
- `DeepResearchServiceTest`：按证据选择角色，专家可并发到达屏障，实际角色及 Judge 发布成对事件。
- `researchExecution.test.js`：按实际 roleCount 去重计数、跟踪活动角色并生成安全的可读证据链接。
- `ChatServicePlannerTest` 与 `ChatMemoryServiceTest`：证据事实映射为来源并随助手消息持久化。
- 最后执行完整 Maven 测试、前端测试与生产构建。
