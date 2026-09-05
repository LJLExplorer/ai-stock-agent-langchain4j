# 简历描述与面试准备

这份材料只使用当前仓库能够由代码、测试或配置证明的事实。不要补写未经压测的 QPS/P95、未经评测的准确率/收益率，也不要把本地 Compose 描述成生产部署。

## 使用原则

- 一份简历选择最匹配岗位的一版项目描述，不要三版叠加。
- 项目标题后给一句定位，再写 3～4 条“问题—设计—结果”。
- “结果”优先写可验证的工程结果，例如默认测试离线化、CI 自动构建、冲突写入被拒绝；没有数据就不要编性能数字。
- 面试前至少能从入口类沿调用链讲到一处测试，不要只背 README。

## Java 后端岗位版本

### 项目名称

Stock Insight Agent｜Java AI 股票研究与知识检索系统

### 一句话描述

基于 Java 21、Spring Boot、MongoDB、Redis 和 Milvus 构建的股票研究后端，通过逐节点 Checkpoint、工具幂等、金融时点约束和证据门禁管理 LLM 与外部数据的不确定性。

### 简历要点

- 设计 Planner 提议、Java 白名单校验、StateGraph 执行的 Plan-and-Execute 链路；图节点成功后以 `executionId + version` CAS 落库，并以 `graphVersion + planHash` 拒绝不兼容恢复。
- 为只读投研工具设计 `executionId:taskId:attempt` 幂等记录，原子保存成功结果与证据快照；恢复时直接复用已成功记录，避免重复调用外部工具。
- 建立 `AnalysisContext -> FinancialFact -> EvidencePack -> ClaimEvidenceGuard` 金融证据链，按分析日截断 K 线、披露日和新闻，并拒绝跨包引用、无引用数字及晚于数据截止日的结论。
- 以 MongoDB 保留完整业务历史，在 Redis 中维护活动/最近话题及按 `userId + sessionId + topicKey` 隔离的模型窗口；通过结构化查询改写识别继续、切换和返回话题，并以“旧摘要 + 较早消息”递归压缩窗口，失败时恢复原始消息。

### 适合追问的关键词

CAS Checkpoint、状态机、工具幂等、At-least-once、时点一致性、证据溯源、有界线程池、SSE 回放、话题级缓存。

## AI Agent / LLM 应用岗位版本

### 项目名称

Stock Insight Agent｜可控 Plan-and-Execute 与 Hybrid RAG

### 一句话描述

基于 LangChain4j 与 LangGraph4j 构建的可恢复股票研究 Agent，以金融时点、证据引用、受控事件和可选多角色审议降低数据穿越、无依据结论与工具失控风险。

### 简历要点

- 将 LLM 限制为候选计划生成器，使用 `PlanValidator` 校验意图、股票代码和任务枚举；工作流以逐节点 Checkpoint 和工具幂等恢复，答案节点不注册工具。
- 将工作流中的行情、技术、财务和新闻结果映射为带稳定 `evidenceId`、时点和来源的 `EvidencePack`；用 Claim–Evidence Guard 确定性拒绝未知 ID、无引用数字和超过数据截止日的日期。
- 在 Milvus 2.5 中构建 Dense COSINE 与 BM25 Sparse 双路检索，以 RRF 融合并用带阈值的 Dense 结果复核候选；结合 Parent/Child 分片让 Child 负责召回，命中后恢复同章节全文或相关窗口。
- 提供默认 `STANDARD` 和可选 `DEEP` 双模式：深度模式固定编排基本面、技术面、新闻、看多、看空、风险与 Judge，通过不含 Prompt/思维链正文的 RunEvent SSE 展示进度；建立 5 样本离线 Agent Eval 回归基线。

### 适合追问的关键词

Agent 可控性、Evidence Grounding、Point-in-time、多角色审议、结构化输出校验、RunEvent/SSE、Agent Eval、RRF、话题路由、记忆污染。

## 校招通用版本

### 项目名称

Stock Insight Agent｜Java 全栈 AI 研究助手

### 一句话描述

独立完成的 Java 全栈 AI 项目，覆盖 Agent 编排、RAG、分层记忆、前后端交互、本地基础设施和持续集成。

### 简历要点

- 使用 Spring Boot + LangChain4j/LangGraph4j 实现股票研究 Agent，接入 7 类业务工具，通过计划白名单、逐节点 Checkpoint、工具幂等和受控重试约束执行。
- 使用 Milvus Dense + BM25 + RRF 构建混合知识检索；将长文按章节构建 Parent/Child 分片，由 Child 负责精确召回，命中后补充同章节相邻内容与 Parent 摘要。
- 使用 MongoDB 保存完整对话与决策复盘，使用 Redis 管理话题级原文窗口和递归摘要；将查询改写结果统一用于检索、长期记忆和任务规划。
- 实现标准/深度双模式 React 界面，通过 SSE 展示计划、证据、多角色审议和结论阶段，支持断线状态补偿与手动重连；配套后端离线测试和前端生产构建。

## 高频追问与回答边界

### 1. 为什么要 Planner + Validator，不能直接让模型调用工具吗？

模型擅长理解意图，但输出不是可信指令。Planner 不注册 Tool，只输出候选计划；Validator 在 Java 中校验固定意图、股票代码和任务枚举。合法计划才进入确定性执行路径。普通开放问题仍保留自主 Tool Calling，但有连续调用次数上限。

代码入口：`AgentPlannerAssistant`、`ChatService.planForExecution`、`PlanValidator`。

### 2. Reflector 和 Critic 都是模型吗？

不是。当前两者都是确定性 Java 规则。Reflector 检查任务状态、空结果、错误关键词和标的一致性；Critic 把结果限制为 `RETRY/ADD_NEWS/ANSWER/FAILED` 路由。当前 `ADD_NEWS` 没有由 Reflector 生成，属于保留扩展点，不能说已经动态补新闻。

代码入口：`WorkflowReflector`、`WorkflowCritic`。

### 3. 四个任务节点是真并行吗？

代码把它们建成从 INIT 分支、在 REFLECTOR 汇合的独立 StateGraph 节点，但项目没有并发执行与性能测试证据。因此只能描述为分支/Fan-out-Fan-in 拓扑，不能承诺并行提速。

### 4. Checkpoint 能从任意节点恢复吗？

现在每个节点动作成功后都会先更新 `lastCompletedNode`，再用 `executionId + version` CAS 保存，成功后才发布 `NODE_COMPLETED`；`CRITIC` 路由也在返回前落库。`resume` 会先校验 `graphVersion` 和 `planHash`，然后从最近 Checkpoint 重新进入图，已成功工具通过幂等记录零调用恢复。

但它不是 LangGraph 原生的任意节点游标续跑，也不保证任意外部副作用 Exactly-once；当前只对四类明确只读的投研工具开放遗留 `STARTED` 的下一 attempt 重试。

代码入口：`WorkflowRunner`、`StockAnalysisWorkflow.stateNode`、`MongoExecutionStateStore`、`MongoToolExecutionStore`。

### 5. 乐观锁冲突为什么不自动重试覆盖？

因为旧状态不能在只知道新版本号的情况下安全合并。Store 只在期望版本匹配时替换；冲突直接抛出，让上层重新加载和重新决策。把旧对象换成最新版本号再次保存会绕过锁并造成丢失更新。

### 6. 为什么 Hybrid Search 后还做 Dense 复核？

RRF 是排名融合分数，不等于语义相似度。候选很少时，不相关文档也可能获得可见排名。因此项目使用同一查询向量跑带 `minScore` 的 Dense 检索，以 `documentId + ingestionVersion + chunkId + content` 验证融合候选，再过滤文档启用/删除状态。

### 7. BM25 与 Dense 分别解决什么问题？

BM25 对股票代码、公司名、指标名等精确词更敏感；Dense 对同义表达和语义近似更稳。RRF 在不强行对齐两种分数量纲的情况下融合名次。代价是两路检索与复核增加查询成本。

### 8. 短期摘要会不会丢信息？

会，任何有损压缩都有风险。当前按话题维护原文窗口，只压缩较早一半并保留最近原文；消息数或字符预算任一到达就触发摘要，避免窗口先淘汰旧消息。摘要为空、超过长度限制或 Redis 更新失败时不提交淘汰结果，并尝试恢复原始窗口；摘要和消息使用相同 TTL。

更严格的场景还可以增加结构化事实槽位、摘要版本和离线忠实度评测。当前没有摘要准确率数据，所以不能说完全无损。

代码入口：`ShortTermSummaryService`、`RedisChatMemoryStore`、`ConversationSummaryAssistant`。

### 9. 多轮追问和话题切换是怎么处理的？为什么不直接拼接完整历史？

完整历史直接拼接会把旧股票、过期时间范围和寒暄一起带入 RAG 与 Planner；单纯截取最近 N 条又无法支持“回到刚才的茅台”。因此项目把业务历史与模型上下文分开：MongoDB 保存完整消息，Redis 保存当前/最近话题以及每个话题独立的 LangChain4j 消息窗口。

每轮先读取最近 30 条业务消息，其中最后 12 条按 6,000 字符预算交给 `QueryRewriteAssistant`。模型结合当前问题、当前话题摘要和最近话题，输出：

```json
{
  "standaloneQuery": "贵州茅台2025年现金流情况",
  "topicKey": "600519",
  "topicRelation": "CONTINUE",
  "confidence": 0.96
}
```

后端不会把这段输出直接当成可信状态：调用失败或空输出时退回原问题，非 JSON 输出按普通改写文本兼容；原问题或改写结果出现明确六位股票代码时，用代码覆盖模型给出的 topicKey 和关系。归一化后的 topicKey 生成稳定 UUID，与 `userId:sessionId` 组合成话题级 memoryId，因此切换股票会进入新窗口，`RETURN` 可以回到旧窗口。

同一个 standaloneQuery 同时交给 RAG、长期记忆召回和 Planner，避免三个模块分别解析出不同标的。最终 Assistant 仍收到用户原话；显式上下文最多选择 8 条与当前话题相关的消息，并过滤“好的、谢谢、收到”等低信息内容。本轮成功保存业务消息后才更新活动话题，失败不会提前改变状态。

这个设计仍有边界：自然语言 topicKey 部分依赖模型，确定性保护目前主要覆盖六位股票代码；消息相关性使用词面匹配，不是单独的语义分类器。面试中应把它描述为“模型识别 + Java 校验 + 话题级隔离”，不要说成完全消除了记忆污染。

代码入口：`QueryRewriteAssistant`、`ConversationQuery`、`ConversationContextService`、`ConversationTopicStore`、`ChatService.resolveRetrievalQuery`。

### 10. 文档如何进行 Parent/Child 分片？

入库先用 Markdown 或中文编号标题划分 Parent Section；再在每个 Parent 内优先按中文段落切分，超长时继续按句子、字符兜底，生成 600～800 字符、80～120 字符重叠的 Child。每个 Child 继承完整 `headingPath`、`stockCode`、`year`、`tags`，并保留 `parentSectionId` 与 `chunkIndex`；只有 Child 写入向量库并参与 Dense/BM25 召回。

命中后只在同一 Parent 内补充前后相邻 Child，并按入库时保存的原始 offset 合并重叠区间、恢复正文顺序。短 Parent 直接返回全文；长 Parent 返回标题路径、抽取式摘要和命中窗口。这样避免整章向量被稀释，也避免单个命中块丢失前后论据。

长 Parent 摘要不调用模型，而是从首段和带有财务关键词、数字、百分比或金额单位的句子中抽取，上限 600 字符。Child 的 Embedding 文本额外拼入完整标题路径；窗口恢复使用入库时保存的原文 offset 合并重叠区间，不依赖字符串去重。

代码入口：`HierarchicalDocumentChunker`、`ParentContextAssembler`、`RetrievalService`。

### 11. 长期记忆如何避免用户串数据？

向量写入携带 `userId/memoryId`，召回先扩大共享候选池，再按用户过滤，并到 MongoDB 校验记录是否启用。但这仍是应用层隔离；没有认证的 `userId` 不能作为生产安全边界。

### 12. MongoDB 与 Milvus 如何保证一致性？

当前不是分布式事务，而是状态标记、重试和补偿。新增元数据失败会清理向量；文档删除先标记、再删除向量、最后删除元数据。极端故障仍需对账任务，所以不能表述为强一致。

### 13. 如何防止模型泄露用户内容到日志？

`TracingChatLanguageModel` 默认用 `<redacted>` 代替模型请求和响应正文；显式开启时仍应用最大长度。核心业务日志已收敛为长度、数量、状态和错误类型；但第三方 SDK、异常链以及显式开启的模型正文仍需要集中脱敏、访问控制和保留周期，不能只依赖一个开关。

### 14. 项目有什么测试证据？

默认 `mvn test` 运行离线单元/组件测试；真实 MongoDB 和 Milvus 连接测试使用 `*IT` 命名并由 Maven Profile 显式执行。仓库当前有 74 个 `*Test.java` 测试类和 2 个 `*IT.java`，前端使用 Node 内置 runner 覆盖深度投研请求、SSE 和进度映射。CI 独立运行 JDK 21 后端测试与前端生产构建。没有发布覆盖率数字就不要口头编一个。

### 15. 历史时点分析如何防止“偷看未来”？

`AnalysisContext` 把标的和 `analysisDate` 作为整条工作流共享的不可变边界。日 K 线只保留截止日之前数据，财务数据按当时已披露的报告选择，新闻过滤未来发布时间。历史数据缺失时不回退为当前值；时点无法确定的事实会标记 `UNKNOWN`。

代码入口：`AnalysisContextResolver`、`MarketDataClient`、`TechnicalAnalysisTool`、`FinancialAnalysisTool`、`NewsRagTool`。

### 16. EvidencePack 和普通把工具结果拼进 Prompt 有什么区别？

EvidencePack 先把工具结果规范化为 `FinancialFact`，每条事实有稳定 `evidenceId`、指标/数值/单位、期间、来源、发布时间和时点状态，同时显式记录数据缺失和工具失败。回答中的数字行必须引用当前证据 ID，跨包 ID 和未来日期会被 Java Guard 拒绝。这不证明证据源本身绝对正确，但能检查“结论是否指向本轮允许的证据”。

代码入口：`FinancialFact`、`EvidencePackBuilder`、`ClaimEvidenceGuard`、`WorkflowAnswerGenerator`。

### 17. 深度投研是自由协作的 Multi-Agent 吗？

不是。它刻意采用固定、有界的编排：基本面、技术面、新闻、看多、看空、风险各最多一次，最后由 Judge 输出结构化 JSON。所有角色共享同一 EvidencePack，不挂载工具或会话记忆。Judge 结果还要经过评级、置信度、日期和证据 ID 校验；角色失败可降级继续，Judge 失效则返回 `INSUFFICIENT_DATA`。这牺牲自由度换取调用上限和权限边界。

代码入口：`DeepResearchAssistant`、`DeepResearchService`、`ResearchConclusion`。

### 18. SSE 事件流如何同时做到可观测和不泄露推理正文？

`RunEvent` 只有固定事件枚举、节点、递增 sequence、时间和最多 500 字符的受控摘要，没有 Prompt、模型响应或工具正文字段。SSE 先回放每个 execution 最近 200 条事件，再从 sequence 游标订阅，终态自动关闭。前端断线后会关闭旧 EventSource，用状态接口补偿一次，由用户手动重连。

代码入口：`RunEvent`、`InMemoryRunEventPublisher`、`ResearchExecutionController`、`frontend/src/researchExecution.js`。

### 19. 决策复盘为什么不放进聊天记忆？

聊天记忆保存用户语境和偏好，决策复盘保存可审计的当时判断与后验结果。`ResearchDecision` 绑定执行 ID、标的、分析日、评级、证据哈希和图版本；`DecisionReviewService` 不调用 LLM，而是计算 1/5/20 个交易日后收益和相对基准。只有在本次分析日已经可见的复盘才能作为校准参考，并且不能充当本轮证据。

代码入口：`ResearchDecisionService`、`DecisionReviewService`、`ChatService.prepareDecisionReviews`。

### 20. Agent Eval 的 1.0 代表模型准确率吗？

不代表。当前 5 个样本与函数式适配器都是固定、离线的，用来保护 Planner、话题路由、RAG、证据和恢复契约不被代码改动破坏。它的 accuracy 1.0、Recall@3 1.0、nDCG@3 0.9197、引用覆盖 1.0 和数字一致性 1.0 只是 fixture 基线。真正的线上质量还需要标注数据集、模型/提示版本、多次采样、成本和延迟统计，因此必须使用显式 Profile，不进入默认 CI。

代码入口：`AgentEvalRunner`、`AgentEvalRunnerTest`、`src/test/resources/eval/agent-eval-cases.json`。

## 不要写进简历的表述

- “生产级高可用 Agent 平台”
- “四任务并行，性能提升 XX%”
- “LangGraph 原生任意节点游标续跑、所有外部副作用 Exactly-once”
- “离线 fixture accuracy 就是线上模型准确率”或“预测收益率 XX%”
- “多角色可自由创建 Agent、任意调工具和相互通信”
- “完成用户鉴权和严格多租户隔离”
- “查询改写能够 100% 准确识别话题，彻底解决上下文污染”
- “实现 MongoDB 与 Milvus 强一致事务”
- “打开模型正文日志也绝对不会包含用户内容”

这些能力当前没有实现或没有可复现实验。主动说明边界通常比堆砌夸大词更能体现工程判断。

## 面试前建议演示

1. 执行 `mvn test`，说明默认测试为什么不连接基础设施。
2. 展示 `PlanValidatorTest` 和 `StockAnalysisWorkflowTest`，讲清模型提议与代码决策边界。
3. 展示 `RetrievalServiceTest`，解释 RRF 分数为何还需语义复核。
4. 展示 `ConversationContextServiceTest` 与 `ChatServiceQueryRewriteTest`，演示继续话题、切换股票、非 JSON 降级和显式代码保护。
5. 展示 `ShortTermSummaryServiceTest`，解释消息数/字符双预算、摘要 TTL 和失败时如何保护原始消息。
6. 展示 `WorkflowRunnerTest` 和 `MongoToolExecutionStoreTest`，解释节点 Checkpoint、版本/计划校验与工具幂等恢复。
7. 展示 `ClaimEvidenceGuardTest`，演示跨包引用、无证据数字和未来日期被确定性拒绝。
8. 在前端切换到深度投研，讲解 executionId、四阶段 RunEvent 时间线、断线补偿和终态收口。
9. 运行 `AgentEvalRunnerTest`，说明 fixture 基线与真实模型评测的边界。
10. 执行 `docker compose config --quiet` 和前端 `npm test && npm run build`，证明仓库具备可复现入口。
