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

基于 Java 21、Spring Boot、MongoDB、Redis 和 Milvus 构建的股票研究后端，通过状态机、乐观锁、补偿机制和测试分层约束 LLM 与外部服务的不确定性。

### 简历要点

- 设计 Planner 提议、Java 白名单校验、StateGraph 执行的 Plan-and-Execute 链路，将行情、技术、财务、新闻任务映射为确定性 Tool 调用，并通过 Reflector/Critic 规则完成失败重试和有限路由。
- 以 MongoDB 保存会话、知识元数据和执行快照，使用 `executionId + version` 条件更新拒绝陈旧写入；对 MongoDB/Milvus 双写采用状态标记、重试和失败补偿，显式处理跨存储一致性。
- 以 MongoDB 保留完整业务历史，在 Redis 中维护活动/最近话题及按 `userId + sessionId + topicKey` 隔离的模型窗口；通过结构化查询改写识别继续、切换和返回话题，并以“旧摘要 + 较早消息”递归压缩窗口，失败时恢复原始消息。
- 面向长篇研究资料设计 Parent/Child 层级分片：按标题、段落、句子逐级切分，以 Child 完成检索；命中后回到 Parent 扩展相邻片段，兼顾召回粒度和章节上下文。

### 适合追问的关键词

乐观锁、状态机、幂等边界、补偿事务、话题级缓存、TTL、进程内锁、测试分层、配置安全。

## AI Agent / LLM 应用岗位版本

### 项目名称

Stock Insight Agent｜可控 Plan-and-Execute 与 Hybrid RAG

### 一句话描述

基于 LangChain4j 与 LangGraph4j 构建的股票研究 Agent，通过受限计划、确定性工具执行、混合检索和分层记忆降低 LLM 幻觉与工具失控风险。

### 简历要点

- 将 LLM 限制为候选计划生成器，使用 `PlanValidator` 校验意图、股票代码和任务枚举；合法计划进入 LangGraph4j 状态图，回答节点使用无工具 Assistant，避免绕过结果校验重新调用工具。
- 在 Milvus 2.5 中构建 Dense COSINE 与 BM25 Sparse 双路检索，以 RRF 融合并用带阈值的 Dense 结果复核候选；结合 Parent/Child 分片让 Child 负责召回，命中后恢复同章节全文或相关窗口。
- 设计“查询改写 + 话题路由 + 上下文筛选”链路：模型基于近期业务消息、当前话题摘要和最近话题输出独立查询及 `NEW/CONTINUE/SWITCH/RETURN`，后端用显式股票代码校正边界，并将同一查询复用于 RAG、长期记忆和 Planner。
- 将完整业务历史、话题级近轮原文、递归摘要和用户长期记忆分层存储；切换股票时进入独立 Redis 模型窗口，返回旧话题时复用稳定话题 ID，并为模型、工作流和工具日志关联 `traceId/sessionId/executionId`。

### 适合追问的关键词

Agent 可控性、Prompt 与代码边界、RRF、语义复核、指代消解、话题路由、上下文预算、记忆污染、Tool Calling 上限。

## 校招通用版本

### 项目名称

Stock Insight Agent｜Java 全栈 AI 研究助手

### 一句话描述

独立完成的 Java 全栈 AI 项目，覆盖 Agent 编排、RAG、分层记忆、前后端交互、本地基础设施和持续集成。

### 简历要点

- 使用 Spring Boot + LangChain4j 实现股票研究对话，接入 7 类业务工具，并通过计划校验、失败重试和无工具回答阶段约束模型行为。
- 使用 Milvus Dense + BM25 + RRF 构建混合知识检索；将长文按章节构建 Parent/Child 分片，由 Child 负责精确召回，命中后补充同章节相邻内容与 Parent 摘要。
- 使用 MongoDB 保存完整对话，使用 Redis 管理最近话题、话题级模型窗口和递归摘要；将指代消解后的独立查询统一用于检索、长期记忆和任务规划，并对显式股票代码提供确定性边界保护。
- 使用 React/Vite 完成会话、知识来源和工具结果展示；补齐 Docker Compose、配置模板、JUnit 测试分层和 GitHub Actions。

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

不能这样宣称。当前在工作流运行前保存初始快照，运行结束后保存最终快照；`resume` 从最近成功持久化的状态重跑，完成任务会跳过。它没有实现每个节点后的持久化，因此进程中途退出可能重做未保存任务。

进一步改进：在节点完成回调中持久化状态，引入幂等键/Outbox，并针对外部副作用定义 Exactly-once 或 At-least-once 语义。

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

默认 `mvn test` 运行离线单元/组件测试；真实 MongoDB 和 Milvus 连接测试使用 `*IT` 命名并由 Maven Profile 显式执行。CI 独立运行 JDK 21 后端测试与 Node 前端生产构建。没有发布覆盖率数字就不要口头编一个。

## 不要写进简历的表述

- “生产级高可用 Agent 平台”
- “四任务并行，性能提升 XX%”
- “任意节点断点续跑、Exactly-once”
- “RAG 准确率 XX%”或“预测收益率 XX%”
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
6. 展示 `WorkflowRunnerTest`，解释为什么乐观锁冲突不能拿旧状态强行覆盖。
7. 执行 `docker compose config --quiet` 和前端 `npm run build`，证明仓库具备可复现入口。
