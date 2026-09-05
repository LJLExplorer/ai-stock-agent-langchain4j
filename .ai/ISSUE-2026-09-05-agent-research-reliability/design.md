# Agent 投研可靠性与深度研究模式设计

## 1. 方案选择

采用“保留现有框架、分层增加可靠性与深度研究”的方案。LangChain4j 继续承担模型与工具接口，LangGraph4j 继续承担工作流，MongoDB/Redis/Milvus 的职责保持不变。新增能力以领域模型、工作流切面和可选分支接入，避免整体迁移造成大面积回归。

未选择整体迁移到 AgentScope Java 或 Spring AI Alibaba，因为现有话题记忆、知识生命周期、Hybrid RAG 和测试体系均已围绕当前框架建立。未选择所有请求强制多角色分析，因为普通问答不应承担多次模型调用、额外延迟和非确定性。

## 2. 总体架构

```text
默认模式
请求 -> Query Rewrite/Topic Routing -> AnalysisContext
     -> RAG + Memory -> Planner/Validator
     -> Deterministic Tools -> EvidencePack
     -> Claim-Evidence Guard -> Answer

深度投研模式
请求 -> AnalysisContext -> Deterministic Tools -> Immutable EvidencePack
                                              -> Fundamental Analyst
                                              -> Technical Analyst
                                              -> News Analyst
                                              -> Bull / Bear
                                              -> Risk Reviewer
                                              -> Judge
                                              -> Structured Conclusion
                                              -> Claim-Evidence Guard
                                              -> Markdown Presenter

横切能力
Node Checkpoint + Tool Idempotency + RunEvent/SSE + Agent Eval + Decision Review
```

默认模式和深度模式共享 AnalysisContext、工具执行、EvidencePack、证据门禁和持久化能力。深度模式只增加只读分析与审议节点，不允许角色自行调用数据工具或修改证据。

## 3. 核心领域模型

### 3.1 AnalysisContext

使用不可变对象统一传递：

- `symbol`
- `analysisDate`
- `researchMode`：`STANDARD` / `DEEP`
- `executionId`
- `traceId`
- `userId`
- `sessionId`

旧请求没有 analysisDate 时由服务端确定当前日期；没有 researchMode 时使用 STANDARD。Planner 和工具不再分别从自由文本推断这些关键字段。

### 3.2 FinancialFact

金融事实不再只存在于工具拼接字符串中。核心字段为：

```text
evidenceId, evidenceType, metric, value, unit, currency,
period, asOf, publishedAt, sourceName, sourceUrl,
retrievedAt, formula, inputSnapshotId, temporalStatus
```

`temporalStatus` 区分 VERIFIED、UNKNOWN 和 REJECTED。字符串展示由 formatter 根据事实生成，避免 LLM 重新计算数值。

### 3.3 EvidencePack

EvidencePack 包含：

- AnalysisContext 摘要；
- 按行情、技术、财务、新闻、RAG 分类的证据；
- 数据缺失与工具失败；
- 证据截止时间和证据哈希；
- 面向模型的有预算文本视图。

构建器完成时间校验、排序、去重和预算控制，并返回不可变集合。角色只接收 EvidencePack 文本视图与证据目录。

### 3.4 ResearchConclusion

Judge 使用结构化 JSON 输出：

```text
rating, confidence, thesis, bullPoints, bearPoints,
risks, missingInformation, evidenceIds, dataAsOf
```

解析失败或引用未知 evidenceId 时进入一次重写；仍失败则退回标准模式答案。

## 4. 工作流持久化与恢复

### 4.1 节点 Checkpoint

扩展 `ExecutionState`：

- `graphVersion`
- `planHash`
- `lastCompletedNode`
- `eventSequence`
- EvidencePack/结论引用或摘要

`StockAnalysisWorkflow` 的节点包装器在动作成功后更新 currentNode/lastCompletedNode，并通过回调立即保存；保存成功才允许图进入后续节点。CRITIC 的 command route 也必须在返回 Command 前保存路由结果。

### 4.2 工具幂等

新增 `ToolExecutionRecord` 和存储接口，唯一键为 `executionId:taskId:attempt`。状态为 STARTED、SUCCEEDED、FAILED。恢复时：

- SUCCEEDED：直接恢复结构化结果；
- STARTED 且无法确认外部结果：对当前只读工具允许按策略重试；
- FAILED：遵循 WorkflowRetryPolicy；
- 未来有副作用工具时必须扩展审批与结果查询，不能沿用只读策略。

### 4.3 图版本保护

首次执行保存稳定 graphVersion 和规范化 planHash。恢复时不一致则拒绝自动执行，返回 `INCOMPATIBLE_CHECKPOINT`，避免新代码解释旧图状态。

## 5. Point-in-time 数据契约

数据适配器统一返回来源时间：

- 日 K 按交易日期过滤到 analysisDate；
- 财报同时记录报告期和披露日期，披露日期未知时标记 UNKNOWN；
- 新闻必须有 publishedAt 且不晚于 analysisDate，否则不进入已验证证据；
- 实时行情只允许用于当前日期分析，历史日期使用对应交易日快照；
- RAG 文档使用文档年份/日期 metadata，未知时间时保留但降低时间可信度。

历史分析不允许召回在 analysisDate 之后形成的决策复盘，避免未来信息泄漏。

## 6. Claim–Evidence 校验

新增独立的 `ClaimEvidenceGuard`，不复用 Markdown 规则。深度模式主要校验 ResearchConclusion 的 evidenceIds 是否存在、是否属于当前 EvidencePack，以及 `dataAsOf` 是否一致。最终 Markdown 中可识别的数字和日期也要能够在引用证据的格式化文本中找到。

校验失败流程：

```text
第一次生成 -> ClaimEvidenceGuard
  -> PASS：保存
  -> FAIL：携带稳定原因码重写一次
       -> PASS：保存
       -> FAIL/异常：确定性降级，列出可验证事实和缺失项
```

现有 `AnswerQualityGuard` 在 ClaimEvidenceGuard 后继续检查 Markdown 完整性。现有关键词式 `factCheck` 标记废弃，不进入主链路。

## 7. 类型化事件与 SSE

定义 sealed `RunEvent` 或等价类型，基础字段包括 executionId、traceId、sequence、occurredAt、eventType、node、summary。第一版事件类型：

- PLAN_CREATED
- NODE_STARTED / NODE_COMPLETED
- TOOL_STARTED / TOOL_COMPLETED / TOOL_FAILED
- WORKFLOW_RETRYING
- EVIDENCE_PACK_READY
- DEEP_RESEARCH_STARTED
- ROLE_COMPLETED
- ANSWER_READY
- WORKFLOW_COMPLETED / WORKFLOW_FAILED

事件发布器同时更新每个 executionId 的有界内存流和 MongoDB 执行状态中的最新序号/摘要。SSE Controller 支持订阅运行中事件；状态查询接口用于断线重连后的补偿读取。SSE 取消订阅不传播为工作流取消。

为保证客户端能在工作流结束前获得 executionId，保留现有同步 `POST /api/chat/send`，并为深度投研新增异步启动接口：服务端先创建会话、executionId 和 PLANNED 状态，返回 `202 Accepted`，再由受控执行器在后台运行。客户端使用 executionId 订阅事件或查询状态。异步执行使用有界线程池和拒绝策略，不使用无界任务队列。

## 8. 决策复盘记忆

`ResearchDecision` 使用独立 MongoDB 集合，保存 userId、symbol、analysisDate、rating、confidence、evidenceHash、结论摘要、评估窗口和模型/图版本。`DecisionReviewService` 在后续分析开始时检查已到期且未评估的记录，通过历史行情计算标的和基准收益，保存结构化 outcome 与 reflection。

复盘召回只使用：

- 相同 userId；
- 相同 symbol；
- 决策日期和结果可用日期均不晚于本轮 analysisDate；
- 已完成评价的记录。

第一版复盘为确定性指标和模板化结论，不让 LLM 自由改写历史事实。

## 9. 深度投研角色

分析角色采用无工具、无记忆 AiService：

- Fundamental Analyst：财务质量、估值和经营变化；
- Technical Analyst：趋势、波动和技术信号；
- News Analyst：事件、来源可信度和时间相关性；
- Bull/Bear：分别组织支持与反对证据；
- Risk Reviewer：检查集中风险、数据缺失、反例和不确定性；
- Judge：基于上述结果和原始 EvidencePack 生成 ResearchConclusion。

角色输出有长度预算和结构约束；默认每个角色最多调用一次，Bull/Bear 不自由互聊。任一分析角色失败时记录缺失，Judge 可以继续；Judge 失败时退回标准答案。

## 10. Agent Eval

离线评测资源放在 `src/test/resources/eval/`，评测代码放在独立 package，避免混入生产对话路径。

- Planner：输入、期望 symbol/tasks；
- Topic Routing：对话、期望 topicKey/relation；
- RAG：查询、相关 document/section；
- Evidence：事实与答案、期望覆盖结果；
- Recovery：初始状态、故障点、期望跳过/重试行为。

结构化报告记录样本数、通过数、各项指标、延迟和调用次数。离线 CI 使用固定返回值；在线 Profile 才调用真实模型和外部服务。README 只展示实际生成的结果。

## 11. 错误处理与降级

- AnalysisContext 无法确定股票：沿用普通聊天，不进入结构化投研。
- 历史时点数据不足：返回部分 EvidencePack 并明确缺失，不回退到未来数据。
- Checkpoint 失败：停止推进并返回可恢复错误。
- Tool 失败：记录结构化失败，按现有有限重试规则处理。
- 深度角色失败：保留成功角色输出；Judge 不可用时回退标准模式。
- 证据门禁失败：一次重写后确定性降级。
- SSE 断开：只取消订阅，不影响执行。
- 决策复盘失败：不阻断当前研究，只记录可重试状态。

## 12. 测试策略

严格按任务执行 RED → GREEN：

- AnalysisContext 默认值和请求兼容；
- 各数据源 analysisDate 过滤及 UNKNOWN/REJECTED；
- FinancialFact/EvidencePack 不可变、去重和预算；
- 节点 Checkpoint 时序、乐观锁冲突和图版本拒绝；
- ToolExecutionRecord 恢复跳过；
- RunEvent 序号、SSE 断连和状态补偿；
- ClaimEvidenceGuard 合法引用、未知引用、数字无来源和降级；
- ResearchDecision 用户/标的/时间隔离和收益计算；
- 深度角色固定调用上限、部分失败和 Judge 降级；
- 离线 Eval 指标计算；
- 完整 Maven 测试、前端生产构建和 `git diff --check`。

## 13. 文档呈现

README 使用一级独立章节“可靠 Agent 运行时与金融证据闭环”，章节内包含总体图、核心机制、默认/深度模式对比、验证证据和诚实边界。原有 RAG 与话题记忆章节继续保留，不把新增能力拆散到多个难以注意的小条目。
