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
- 使用 Redis 实现按用户与会话隔离的短期消息窗口，并通过“旧摘要 + 淘汰消息”递归压缩控制上下文；摘要写入失败时恢复原始窗口，避免静默丢失。
- 面向长篇研究资料设计 Parent/Child 层级分片：按标题、段落、句子逐级切分，以 Child 完成检索；命中后回到 Parent 扩展相邻片段，兼顾召回粒度和章节上下文。
- 将默认 Maven 测试与真实 MongoDB/Milvus 集成测试分层，建立 JDK 21 后端测试和 Node 前端构建 CI，并提供固定版本 Docker Compose 与脱敏配置模板。

### 适合追问的关键词

乐观锁、状态机、幂等边界、补偿事务、进程内锁、测试分层、配置安全。

## AI Agent / LLM 应用岗位版本

### 项目名称

Stock Insight Agent｜可控 Plan-and-Execute 与 Hybrid RAG

### 一句话描述

基于 LangChain4j 与 LangGraph4j 构建的股票研究 Agent，通过受限计划、确定性工具执行、混合检索和分层记忆降低 LLM 幻觉与工具失控风险。

### 简历要点

- 将 LLM 限制为候选计划生成器，使用 `PlanValidator` 校验意图、股票代码和任务枚举；合法计划进入 LangGraph4j 状态图，回答节点使用无工具 Assistant，避免绕过结果校验重新调用工具。
- 在 Milvus 2.5 中构建 Dense COSINE 与 BM25 Sparse 双路检索，通过 RRF 融合排序，并用带阈值的 Dense 结果复核候选；Hybrid 异常时支持可配置的单路语义降级。
- 实现中文 Parent/Child 层级分片：Child 携带标题路径和金融上下文参与向量检索；命中后合并同章节相邻片段，短章节返回全文，长章节返回摘要与相关窗口，减少孤立片段造成的上下文缺失。
- 构建近轮原文、递归摘要、用户长期记忆三层上下文；查询重写只服务检索，长期记忆扩大候选池后按 `userId` 和启用状态过滤。
- 为模型调用、工作流路由和工具执行关联 `traceId/sessionId/executionId`；模型请求与响应默认脱敏，显式开启诊断时仍受长度限制。

### 适合追问的关键词

Agent 可控性、Prompt 与代码边界、RRF、语义复核、查询重写、记忆污染、Tool Calling 上限。

## 校招通用版本

### 项目名称

Stock Insight Agent｜Java 全栈 AI 研究助手

### 一句话描述

独立完成的 Java 全栈 AI 项目，覆盖 Agent 编排、RAG、分层记忆、前后端交互、本地基础设施和持续集成。

### 简历要点

- 使用 Spring Boot + LangChain4j 实现股票研究对话，接入 7 类业务工具，并通过计划校验、失败重试和无工具回答阶段约束模型行为。
- 使用 Milvus Dense + BM25 + RRF 构建混合知识检索，结合 MongoDB 文档状态过滤和失败降级返回可追溯知识来源。
- 将长文按章节构建 Parent/Child 分片，Child 负责精确召回，命中后补充同章节相邻内容与 Parent 摘要，使回答既能定位细节又保留上下文。
- 使用 Redis 管理短期窗口和递归摘要，使用 MongoDB/Milvus 保存用户主动录入的长期记忆，实现多轮查询重写和按用户过滤。
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

会，任何有损压缩都有风险。当前只压缩较早一半消息，保留最近原文，并限制摘要为空或超长时不能淘汰窗口；更新失败会尝试回滚。更严格场景可增加事实槽位、摘要版本和离线评测。

### 9. 文档如何进行 Parent/Child 分片？

入库先用 Markdown 或中文编号标题划分 Parent Section；再在每个 Parent 内优先按中文段落切分，超长时继续按句子、字符兜底，生成 600～800 字符、80～120 字符重叠的 Child。每个 Child 继承完整 `headingPath`、`stockCode`、`year`、`tags`，并保留 `parentSectionId` 与 `chunkIndex`；只有 Child 写入向量库并参与 Dense/BM25 召回。

命中后只在同一 Parent 内补充前后相邻 Child，重叠窗口按 `chunkIndex` 去重、顺序拼接。短 Parent 直接返回全文；长 Parent 返回标题路径、抽取式摘要和命中窗口。这样避免整章向量被稀释，也避免单个命中块丢失前后论据。

代码入口：`HierarchicalDocumentChunker`、`ParentContextAssembler`、`RetrievalService`。

### 10. 长期记忆如何避免用户串数据？

向量写入携带 `userId/memoryId`，召回先扩大共享候选池，再按用户过滤，并到 MongoDB 校验记录是否启用。但这仍是应用层隔离；没有认证的 `userId` 不能作为生产安全边界。

### 11. MongoDB 与 Milvus 如何保证一致性？

当前不是分布式事务，而是状态标记、重试和补偿。新增元数据失败会清理向量；文档删除先标记、再删除向量、最后删除元数据。极端故障仍需对账任务，所以不能表述为强一致。

### 12. 如何防止模型泄露用户内容到日志？

`TracingChatLanguageModel` 默认用 `<redacted>` 代替模型请求和响应正文；显式开启时仍应用最大长度。核心业务日志已收敛为长度、数量、状态和错误类型；但第三方 SDK、异常链以及显式开启的模型正文仍需要集中脱敏、访问控制和保留周期，不能只依赖一个开关。

### 13. 项目有什么测试证据？

默认 `mvn test` 运行离线单元/组件测试；真实 MongoDB 和 Milvus 连接测试使用 `*IT` 命名并由 Maven Profile 显式执行。CI 独立运行 JDK 21 后端测试与 Node 前端生产构建。没有发布覆盖率数字就不要口头编一个。

## 不要写进简历的表述

- “生产级高可用 Agent 平台”
- “四任务并行，性能提升 XX%”
- “任意节点断点续跑、Exactly-once”
- “RAG 准确率 XX%”或“预测收益率 XX%”
- “完成用户鉴权和严格多租户隔离”
- “实现 MongoDB 与 Milvus 强一致事务”
- “打开模型正文日志也绝对不会包含用户内容”

这些能力当前没有实现或没有可复现实验。主动说明边界通常比堆砌夸大词更能体现工程判断。

## 面试前建议演示

1. 执行 `mvn test`，说明默认测试为什么不连接基础设施。
2. 展示 `PlanValidatorTest` 和 `StockAnalysisWorkflowTest`，讲清模型提议与代码决策边界。
3. 展示 `RetrievalServiceTest`，解释 RRF 分数为何还需语义复核。
4. 展示 `ShortTermSummaryServiceTest`，解释摘要失败如何保护原始消息。
5. 展示 `WorkflowRunnerTest`，解释为什么乐观锁冲突不能拿旧状态强行覆盖。
6. 执行 `docker compose config --quiet` 和前端 `npm run build`，证明仓库具备可复现入口。
