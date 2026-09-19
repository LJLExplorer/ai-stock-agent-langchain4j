# 简历描述与面试问答

这份材料按当前仓库实现编写。简历部分可按岗位选用，口述部分用于组织回答，源码与测试入口用于准备追问。项目周期、团队规模、个人分工和上线效果请按真实经历补充；只保留自己实际负责、能解释设计和验证过程的内容。

## 简历项目描述

### Java 后端岗位

**Stock Insight Agent｜Java AI 股票研究系统**

技术栈：Java 21、Spring Boot、LangChain4j、LangGraph4j、MongoDB、Redis、Milvus。

面向股票研究场景构建后端服务，支持多轮对话、行情与财务分析、知识检索和异步深度投研，重点处理外部调用失败、并发状态更新和长任务恢复。

- 设计 Plan-and-Execute 工作流，将本地/模型候选计划经 Java 白名单校验后映射到四类只读工具；通过只读图状态、任务增量和按 taskId 合并支持分支并行，避免共享对象修改与结果覆盖。
- 实现串行节点及并行汇合点的 MongoDB CAS Checkpoint，使用图版本、计划哈希和 nextNode 校验并恢复执行；以 executionId、taskId、attempt 保存工具成功快照，恢复时复用已完成调用。
- 构建结构化工具结果与金融证据校验链，检查 Schema、标的、时点、来源、数值和必需指标；将问题任务纳入有界重试，并在模型入口重新验证恢复快照。
- 拆分对话上下文、Agent 执行、响应组装和持久化职责；在 Redis 中维护话题窗口与递归摘要，通过 Lua 比对旧消息和旧摘要后原子压缩，避免摘要生成期间覆盖新消息。

如岗位更重视异步服务，可将最后一条替换为：

- 实现异步深度投研接口，以有界线程池控制接单量，通过带 sequence 的 SSE 事件回放、状态查询补偿和终态处理展示任务进度；进程启动时补偿遗留非终态执行。

### AI Agent / LLM 应用岗位

**Stock Insight Agent｜Plan-and-Execute 与 Hybrid RAG 研究助手**

技术栈：LangChain4j、LangGraph4j、Spring Boot、Milvus、MongoDB、Redis、React。

围绕股票研究中的任务规划、证据不足和多轮上下文问题，构建可校验、可追踪的 Agent 应用，提供标准分析与深度投研两种模式。

- 将计划生成、工具执行与答案生成分离：本地规则或 Planner 提出计划，Validator 校验后交给状态图执行；Reflector/Critic 采用确定性规则，答案节点使用无工具模型并校验输出。
- 建立 AnalysisContext、FinancialFact 和 EvidencePack，统一分析日期、结构化指标与来源；校验当前工具快照及证据覆盖，回答阶段检查证据 ID、数值引用和日期边界。
- 实现 Milvus Dense + BM25 + RRF 混合检索，保留关键词独有命中；按文档状态与活动版本过滤后，以 Parent/Child 分层恢复章节全文或相关窗口。
- 实现话题级记忆和可选深度投研：独立查询统一用于检索、记忆召回与规划；适用角色基于共享证据并行分析，由单次 Judge 裁决，再由 Java 校验结构化结论，配套离线契约评测。

### 校招通用岗位

**Stock Insight Agent｜Java 全栈 AI 研究助手**

技术栈：Java 21、Spring Boot、LangChain4j/LangGraph4j、MongoDB、Redis、Milvus、React、Vite。

实现覆盖股票研究、知识库和会话管理的全栈应用，提供标准问答、异步深度投研和来源展示。

- 接入 7 个业务工具，针对行情、技术、财务和新闻构建计划校验、并行执行、失败重试和检查点恢复链路。
- 使用 Dense、BM25 和 RRF 构建混合检索，将长文按 Parent/Child 分层切分，结合版本过滤和父章节扩展组织回答上下文。
- 使用 MongoDB 保存业务历史，Redis 保存话题状态、近期消息与递归摘要，支持多轮追问、切换股票和返回旧话题。
- 实现 React 标准/深度模式与 SSE 进度展示，提供本地 Compose、离线后端测试、前端测试和 CI 构建入口。

一份简历选一版、保留 3～4 条即可。结果优先写可验证的行为，如“冲突写入被拒绝”“恢复复用成功工具记录”，没有实验依据就不补写准确率、性能提升或收益数字。

## 一分钟项目介绍

> 这是一个基于 Java 的股票研究 Agent，用户可以查询行情、技术指标、财务和新闻，也可以导入资料做知识库问答。
>
> 我重点解决的是模型与外部数据进入后端之后，怎么保证执行过程可控。系统先用本地规则或模型生成计划，再由 Java 校验；合法计划进入 LangGraph4j 状态图，并行调用需要的工具。工具返回后还要检查结构、股票、时间和证据，失败任务只在预算内重试。
>
> 执行状态保存在 MongoDB，恢复时按检查点继续，并复用成功的工具记录。回答基于结构化证据生成，再检查引用和输出质量。多轮对话则通过话题级 Redis 窗口和递归摘要保持上下文。
>
> 当前我主要用离线测试验证并发、恢复和证据边界；真实模型质量和线上性能还需要独立评测。

按实际分工调整“我”的表述。展开时选一条最熟悉的链路讲透，例如“并行分支如何合并并恢复”或“工具成功为什么仍可能被拒绝”。

## 架构与工程设计

### 1. 一次请求经过哪些模块？

`ChatService` 先解析会话，再让 `ConversationContextService` 补全问题、改写独立查询并选择话题记忆。独立查询交给 RAG、长期记忆召回和 `AgentExecutionService`；后者根据开关和计划选择工作流或助手。结果由 `ResponseAssembler` 组装，最后由 `ConversationPersistenceService` 保存业务消息，再推进话题和刷新摘要。

这里区分用户原文、补全后的模型问题和独立检索查询：原文用于业务记录，独立查询用于检索和规划。工作流已有最终答案时直接复用，不再次调用通用助手改写。

入口：[ChatService](../src/main/java/com/ljl/ai/service/ChatService.java)、[职责拆分说明](chat-service-refactoring.md)。验证：[ChatServiceOrchestrationTest](../src/test/java/com/ljl/ai/service/ChatServiceOrchestrationTest.java)。

### 2. 为什么拆 ChatService？会不会只是把代码搬到别的类？

拆分依据是职责和失败语义。上下文服务负责准备输入，执行服务负责选路与调用，组装器负责来源和响应，持久化服务负责保存顺序，失败处理器负责受控提示和记忆清理。中间结果使用字段明确的 record 传递，主服务负责协调。

验证也围绕行为：消息保存失败后不能继续推进话题；异步请求必须沿用预分配 executionId；响应只能收集本轮工具记录；同一会话的锁必须覆盖读取上下文到保存消息的全程。拆类不会自动产生跨存储事务，这些失败语义仍需要明确维护。

验证：[ChatServiceOrchestrationTest](../src/test/java/com/ljl/ai/service/ChatServiceOrchestrationTest.java)、[ChatServiceWiringTest](../src/test/java/com/ljl/ai/service/ChatServiceWiringTest.java)、[ResponseAssemblerTest](../src/test/java/com/ljl/ai/service/ResponseAssemblerTest.java)。

### 3. 为什么用 Planner + Validator？每次都调用规划模型吗？

明确请求优先走本地解析，不能得到有效计划时才调用 Planner。Planner 不注册工具，只提出候选计划；Java 校验意图、股票代码和任务枚举，规范化标的并去重任务。合法计划才进入确定性工具映射。

普通开放问题保留通用 Tool Calling 降级路径，并设置连续调用次数上限。因此应说明“股票工作流受确定性计划约束”，不能声称所有请求都经过同一套任务与证据门禁。

入口：[AgentExecutionService](../src/main/java/com/ljl/ai/service/AgentExecutionService.java)、[PlanValidator](../src/main/java/com/ljl/ai/planner/PlanValidator.java)。验证：[AgentExecutionPlannerTest](../src/test/java/com/ljl/ai/service/AgentExecutionPlannerTest.java)。

### 3.1 Planner 输出是 OpenAI 规定的 Tool Calling 格式吗？

不是。当前 Planner 只返回项目定义的候选计划 JSON，例如 `{"intent":"STOCK_ANALYSIS","symbol":"600519.SH","tasks":["MARKET_DATA"]}`；`AgentExecutionService` 将这个字符串解析为 `AgentPlan`，再交由 Java Validator 校验。它不含 OpenAI 工具调用中的 `tool_calls`、调用 ID、函数名和参数等协议字段。

这个边界是有意设计的：Planner 不注册业务工具，只描述“要分析什么、需要哪些受限任务”；真正的工具权限、参数补全、执行顺序和重试策略都在后端工作流中确定。若直接把 Planner 的自由文本当成可执行指令，模型可能选到未授权工具、给出不完整参数或重复调用。

入口：[AgentPlannerAssistant](../src/main/java/com/ljl/ai/agent/AgentPlannerAssistant.java)、[AgentExecutionService](../src/main/java/com/ljl/ai/service/AgentExecutionService.java)。

### 3.2 那什么情况下应该使用原生 Tool Calling，什么情况下用业务计划？

我按执行路径的确定性划分。简单、开放的问答适合让模型通过原生 Tool Calling 自主选择少量工具；协议层由模型适配器发送工具 Schema，模型返回工具名和 JSON 参数，应用执行后把结果回传模型，直到模型给出最终回答。

股票标准分析的工具集合、数据时点、执行权限和失败处理较确定，因此采用“业务计划 + 后端工作流”更合适：模型或本地规则只给出 `symbol` 和任务枚举，Java 白名单校验后由状态图执行。这样可审计、可重试、可恢复，也不会把下单类等未来高风险工具暴露给模型自由选择。深度投研这类多阶段任务可混合使用：代码固定关键状态与审批边界，模型在受限节点内完成路由、总结或专用工具选择。

这不是“工作流比 Agent 更先进”，而是按风险和确定性取舍：固定步骤用代码编排，开放步骤才交给模型决策。

### 3.3 当前项目的工具调用是规范的 Tool Calling 吗？

需要区分两条链路。通用对话降级路径中，`StockAnalysisAssistant` 通过 LangChain4j `@Tool` 注册 `getRealtimeQuote` 等方法；模型会依据工具定义选择调用，框架执行 Java 方法并回传结果。底层若配置为 OpenAI 或 OpenAI 兼容模型，LangChain4j 会适配为该提供商的原生 function/tool-calling 协议。这是标准的 LLM Tool Calling。

而股票计划校验成功后，`WorkflowRunner` 会根据已批准的任务直接调用 `StockAnalysisTaskExecutor`。这不是模型返回的 `tool_call`，而是受控的后端服务调用；同样是规范设计，但术语上应称为“工作流工具执行”，不能称为 OpenAI Tool Calling。它有任务状态、幂等记录、重试和证据校验，因此更适合本项目的核心投研链路。

面试中可以概括为：**“普通开放问题保留模型原生 Tool Calling；股票分析主路径使用 Planner + Validator + Workflow，将工具执行权收回到后端。”** 观测上应记录调用来源（例如 `LLM_TOOL_CALL` 或 `WORKFLOW`），避免把两类执行混在同一条指标里。

入口：[AgentConfig](../src/main/java/com/ljl/ai/agent/AgentConfig.java)、[StockAnalysisTaskNode](../src/main/java/com/ljl/ai/workflow/StockAnalysisTaskNode.java)。

### 4. 为什么引入状态图，直接写几个 if/else 不行吗？

固定的一次性调用用普通方法就可以。这个项目有并行任务、问题任务重试、证据检查、标准/深度分支和检查点恢复，需要显式保存“已经完成什么、为什么重试、下一步去哪”。状态图把这些状态与路由集中表达，也让节点可以独立验证。

代价是要维护状态 Schema、增量合并规则和图版本。项目使用 LangGraph4j 编排节点，MongoDB 快照、兼容性检查和工具幂等是应用层自己实现的。

入口：[StockAnalysisWorkflow](../src/main/java/com/ljl/ai/workflow/StockAnalysisWorkflow.java)、[WorkflowRunner](../src/main/java/com/ljl/ai/workflow/WorkflowRunner.java)。

### 5. 四个工具任务是真并行吗？怎么避免共享状态冲突？

四类分支从 `DISPATCH` 分发，通过 `addParallelNodeExecutor` 配置四线程执行器，在 `TASKS_JOIN` 汇合；只执行计划中存在的任务。图状态使用深度只读数据，每个分支返回自身任务的 delta，不能修改兄弟任务。合并时任务按 taskId 更新，事件序号取最大值，证据包在汇合后统一重建。

分支不会各自用同一个 version 写整份 MongoDB 快照，汇合后才做一次 CAS。测试通过四分支同时到达的屏障验证重叠执行和结果完整性；可以说实现了并行，不能据此说延迟降低了某个比例。每次图运行都有独立线程池，生产扩展仍需考虑总并发量。

入口：[WorkflowAgentState](../src/main/java/com/ljl/ai/workflow/WorkflowAgentState.java)。验证：[StockAnalysisWorkflowTest](../src/test/java/com/ljl/ai/workflow/StockAnalysisWorkflowTest.java)、[WorkflowNodeDeltaTest](../src/test/java/com/ljl/ai/workflow/WorkflowNodeDeltaTest.java)。

### 6. Checkpoint 保存在哪里？能恢复到哪一步？

串行节点和并行汇合点将 `ExecutionState` 保存到 MongoDB，包含任务、结果、裁决、重试状态和 nextNode。节点结果与下一步路由一起 CAS 提交，成功后才发布完成事件。

显式调用 `resume(executionId)` 时，先检查 graphVersion 和 planHash，再从 nextNode 进入图。例如 CRITIC 已提交就从 EVIDENCE_PACK 继续；工具成功但汇合尚未保存，则重走分发并复用工具成功记录。当前不是 LangGraph4j 原生 CheckpointSaver，也不是任意外部调用的指令级续跑。

入口：[WorkflowRunner](../src/main/java/com/ljl/ai/workflow/WorkflowRunner.java)。验证：[WorkflowRunnerTest](../src/test/java/com/ljl/ai/workflow/WorkflowRunnerTest.java)。

### 7. CAS 冲突为什么不换个最新版本号再写？

版本号保护的是整份状态的因果关系。旧快照读到版本 5 时，只能在数据库仍为版本 5 的条件下更新；如果别人已提交版本 6，替换版本号后重写旧内容会丢掉别人的更新。

当前冲突直接拒绝写入，上层应重新加载并判断下一步。异常处理也基于最后成功提交的快照，不能用失败分支中的旧状态覆盖其他执行者的结果。

入口：[MongoExecutionStateStore](../src/main/java/com/ljl/ai/workflow/MongoExecutionStateStore.java)。验证：[MongoExecutionStateStoreTest](../src/test/java/com/ljl/ai/workflow/MongoExecutionStateStoreTest.java)、[WorkflowRunnerTest](../src/test/java/com/ljl/ai/workflow/WorkflowRunnerTest.java)。

### 8. 工具幂等能做到 Exactly-once 吗？

当前记录键是 `executionId:taskId:attempt`，一次尝试从 STARTED 转为 SUCCEEDED 或 FAILED，成功结果和证据快照一起保存。恢复时直接复用成功记录，避免再次调用已经完成的工具。

但如果外部调用成功后、保存结果前进程崩溃，数据库只看到 STARTED，无法证明外部是否已经执行。因此对当前四类只读工具允许新 attempt 重试，整体是 at-least-once。若将来接入下单等写操作，需要外部服务支持业务幂等键、结果查询和对账，不能照搬只读工具策略。

入口：[MongoToolExecutionStore](../src/main/java/com/ljl/ai/workflow/MongoToolExecutionStore.java)。验证：[MongoToolExecutionStoreTest](../src/test/java/com/ljl/ai/workflow/MongoToolExecutionStoreTest.java)。

### 9. 服务重启后会自动恢复深度投研吗？

当前不会。`WorkflowRunner.resume()` 是显式恢复能力；异步研究的队列和事件缓冲在进程内。启动时 `StartupRecoveryRunner` 将上次遗留的非终态执行，包括只接单未运行的占位记录，标记为失败，提示用户重新发起。补偿失败会阻止应用就绪。

这套启动策略要求单实例部署。要支持多实例自动接管，需要持久任务队列、执行租约或分布式锁、失效实例识别和外部事件流，不能简单让多个实例共享同一业务库。

入口：[StartupRecoveryRunner](../src/main/java/com/ljl/ai/listener/StartupRecoveryRunner.java)、[ResearchExecutionService](../src/main/java/com/ljl/ai/service/ResearchExecutionService.java)。

## 工具结果、证据与深度投研

### 10. Reflector 和 Critic 是模型吗？工具返回 success 为什么还要检查？

两者都是 Java 规则。success 只能说明工具调用成功，返回值仍可能缺字段、股票不匹配、过期或缺少可引用证据。Reflector 检查结构化快照及本次证据，输出带 taskId、code、field 的问题列表；Critic 在有限路由内决定重试、回答或失败。

当前不再靠正文出现“异常”“失败”来判断结果无效，正常负面新闻也应被保留。`ADD_NEWS` 是保留路由，当前 Reflector 不会自动追加 Planner 未提出的新闻任务。

入口：[WorkflowReflector](../src/main/java/com/ljl/ai/workflow/WorkflowReflector.java)、[WorkflowResultValidator](../src/main/java/com/ljl/ai/workflow/WorkflowResultValidator.java)。验证：[WorkflowResultValidatorTest](../src/test/java/com/ljl/ai/workflow/WorkflowResultValidatorTest.java)。

### 11. 结构化工具结果具体校验什么？

行情使用 StockQuote，技术与财务使用含 BigDecimal 指标的 AnalysisToolPayload，新闻使用 NewsItem 列表。校验包括 JSON 形状和类型、必需指标、标的一致性、数据及披露时间、HTTP(S) 来源、数值范围，以及快照与证据是否对应。

例如技术任务要求 close、changePercent、ma5、ma20，不能拿一段“均线向好”的文本代替。利润、现金流和增长率允许负值；数值范围按指标定义，不能给所有百分比套同一个上限。各类证据还配置了不同的时效门槛，这些是项目策略，不是行业统一标准。

细则：[工作流校验说明](workflow-validation.md)。验证：[StructuredToolWorkflowTest](../src/test/java/com/ljl/ai/workflow/StructuredToolWorkflowTest.java)。

### 12. 历史分析怎么避免使用未来数据？

先把 analysisDate 固定在 AnalysisContext 中，并传给工具。K 线截断到分析日，财务同时关注报告期与披露日，新闻按发布时间筛选。比如一份年报的报告期属于去年，但今年才披露，就不能用于披露日前的历史判断。

时间未知的数据不能当作已验证事实，历史缺失也不能拿当前行情补上。这里约束的是本系统可接纳的数据时点，不能证明供应商的历史数据没有后续修订。

入口：[AnalysisContext](../src/main/java/com/ljl/ai/research/AnalysisContext.java)。验证：[PointInTimeDataContractTest](../src/test/java/com/ljl/ai/client/PointInTimeDataContractTest.java)。

### 13. EvidencePack 与把工具结果拼进 Prompt 有什么区别？能消除幻觉吗？

EvidencePack 先将结构化指标和来源映射为 FinancialFact，保存 evidenceId、值、单位、时间与来源，并单独记录缺失和失败。模型拿到的是从这些事实渲染的有界上下文，展示长文本不再是事实解析入口。

生成后检查引用是否属于本轮有效证据，含数值表达的内容是否带引用，日期是否越界，再检查重复和异常文本。标准工作流回答最多纠正一次，仍不通过则降级。这能拦截一部分明确违规输出，但“引用存在”不等于“证据支持整句话”，不能称为完全消除幻觉或逐句事实核验。

入口：[EvidencePackBuilder](../src/main/java/com/ljl/ai/research/EvidencePackBuilder.java)、[ClaimEvidenceGuard](../src/main/java/com/ljl/ai/research/ClaimEvidenceGuard.java)。验证：[ClaimEvidenceGuardTest](../src/test/java/com/ljl/ai/research/ClaimEvidenceGuardTest.java)。

### 14. 从 ANSWER 节点恢复，会不会绕过之前的校验？

模型入口会重新验证当前任务快照，并重建 EvidencePack。检查点中的 trusted、modelView 或 evidenceHash 都不能直接作为放行依据；有效任务缺少证据包时可以重建，任务无效则在调用模型前拒绝。

同样，重试后只能使用本次 currentEvidence。历史结果和历史证据保留用于审计，不能补齐本次缺失指标。图协议升级为 stock-analysis-v3，未完成的旧版快照拒绝恢复，避免旧协议绕过新校验。

验证：[WorkflowEvidenceBoundaryTest](../src/test/java/com/ljl/ai/workflow/WorkflowEvidenceBoundaryTest.java)。

### 15. 新闻搜索怎么过滤低质量结果？官方链接就可信吗？

先构造公司新闻或公告主题查询，不把用户整段策略、教程指令原样交给搜索引擎。媒体与官方来源都要尝试；候选经过内容类型、主体、HTTP(S) 链接和发布时间筛选，媒体再按配置进行语义过滤，官方结果还要在本地校验域名。

官方报告目录可以证明发现了某份报告的入口和披露日期，不能据此声称已解析 PDF 全文或验证财务指标。财务数据 API 链接也应保留真实来源身份。搜索有补查预算，工作流另有任务重试预算，两者会嵌套，不能将搜索轮数当作总外部调用次数。

入口：[NewsSearchClient](../src/main/java/com/ljl/ai/client/NewsSearchClient.java)。验证：[NewsSearchClientTest](../src/test/java/com/ljl/ai/client/NewsSearchClientTest.java)。流程：[工具与证据质量说明](tool-execution-and-evidence-quality.md)。

### 15.1 新闻返回类型不对时，是把错误原样交给模型重试吗？

不是。协议边界先剔除缺标题、摘要、来源或合法链接的候选；如果持久化快照仍不合法，`WorkflowResultValidator` 产生 `result[i].field` 级问题。恢复开关关闭时沿用有限确定性重试；开启后，`NewsRecoveryAdvisor` 只看到原查询、当前窗口和结构化问题，只能返回调整关键词、缩短窗口、只查官方公告或资料不足。

模型建议不是工具调用指令。Java 会校验动作白名单、查询长度、1～30 天范围、窗口确实缩短以及参数没有重复；非法 JSON、越界值、重复建议或模型异常会转为 `INSUFFICIENT_DATA`，停止机械重试。这样保留模型对检索策略的判断能力，同时不让模型放宽 Schema 或自行扩张工具权限。

### 16. 深度投研是自由协作的 Multi-Agent 吗？

它采用固定、有界的角色编排。根据证据范围选择适用的基本面、技术面和新闻角色，再结合看多、看空、风险视角；角色并行生成独立意见，按预定顺序收集后交给单次 Judge。每个角色最多调用一次，共享同一个证据包，不挂载工具和会话记忆。

角色异常输出会被丢弃并记录限制；Judge JSON 还要检查评级、置信度范围、日期、正文和证据 ID。部分角色失败可以降级继续，Judge 失败或没有有效角色则返回证据不足。它提供的是受控的多视角分析，没有自由创建 Agent、任意通信或自动扩权能力。

入口：[DeepResearchService](../src/main/java/com/ljl/ai/research/DeepResearchService.java)。验证：[DeepResearchServiceTest](../src/test/java/com/ljl/ai/research/DeepResearchServiceTest.java)、[WorkflowAnswerGeneratorTest](../src/test/java/com/ljl/ai/workflow/WorkflowAnswerGeneratorTest.java)。

### 17. SSE 如何避免断线后进度错乱？

事件带 executionId、递增 sequence、固定类型、节点和受控摘要。服务端保留每次执行最近 200 条事件，回放后按游标补发订阅间隙事件。前端断线后关闭旧 EventSource，查询状态补偿；任务未结束时显示手动重连，终态则收口。

断线不会取消后台研究。事件流不含 Prompt、模型思考和工具正文，但缓存有界且只在内存里，不能保证无限历史回放或跨实例消费。后台默认 2 个工作线程、32 个排队位，队列满时受控拒绝接单。

入口：[InMemoryRunEventPublisher](../src/main/java/com/ljl/ai/observability/InMemoryRunEventPublisher.java)、[前端执行客户端](../frontend/src/researchExecution.js)。验证：[ResearchExecutionServiceTest](../src/test/java/com/ljl/ai/service/ResearchExecutionServiceTest.java)、[前端执行测试](../frontend/src/researchExecution.test.js)。

### 18. 决策复盘为什么不放进聊天记忆？

聊天记忆保留用户语境和偏好，决策复盘记录当时结论及后来结果。ResearchDecision 绑定执行 ID、标的、分析日、评级、置信度、证据哈希与图版本；复盘服务使用历史 K 线确定性计算后续 1/5/20 个交易日的收益及相对基准收益，不调用 LLM。

只召回同一用户、同一标的，且后验结果在本次 analysisDate 已可见的复盘。它作为校准参考单独传入，不能充当本轮 evidenceId，也不能据此说系统已经自动训练或优化交易策略。

入口：[DecisionReviewService](../src/main/java/com/ljl/ai/research/DecisionReviewService.java)、[ResearchDecisionService](../src/main/java/com/ljl/ai/research/ResearchDecisionService.java)。

## RAG、记忆与存储

### 19. BM25、Dense 和 RRF 分别解决什么问题？为什么不再做 Dense 交集复核？

BM25 补充代码、公司名和指标名等精确词匹配，Dense 处理语义近似；RRF 按排名融合，避免直接混合量纲不同的分数。如果融合后再强制与 Dense 命中取交集，BM25 独有结果会被删掉，双路召回就失去了互补性。

当前保留 RRF 候选，先过滤文档状态与活动版本，再扩展 Parent。RRF 分数不是余弦相似度，不能使用 Dense 的 minScore 阈值；该阈值只用于单路 Dense 路径。现在尚无独立 Reranker，后续可在候选过滤后评估重排收益和成本。

入口：[RetrievalService](../src/main/java/com/ljl/ai/rag/RetrievalService.java)。验证：[RetrievalServiceTest](../src/test/java/com/ljl/ai/rag/RetrievalServiceTest.java)。

### 20. 为什么使用 Parent/Child，分块参数怎么选？

召回需要较小、主题集中的片段，回答需要保留论据上下文。入库先识别标题层级生成 Parent，再按段落、句子和字符边界生成 Child；默认目标 700 字符，常规范围 600～800，重叠 80～120，短章节和尾块按实际长度处理。Child 的索引文本加入完整标题路径。

命中后短 Parent 返回全文，长 Parent 返回抽取式摘要与命中块相邻窗口，按原始 offset 合并重叠区间。这增加了存储元数据和 Parent 查询成本。当前参数是工程默认值，若要证明最优，需要在标注查询集上比较 Recall@K、上下文长度和延迟。

入口：[HierarchicalDocumentChunker](../src/main/java/com/ljl/ai/knowledge/HierarchicalDocumentChunker.java)、[ParentContextAssembler](../src/main/java/com/ljl/ai/rag/ParentContextAssembler.java)。

### 21. 怎么处理“那它去年呢”和“回到刚才的茅台”？

每轮把当前问题、近期业务消息、当前话题摘要和最近话题交给 QueryRewriteAssistant，得到 standaloneQuery、topicKey、topicRelation 和 confidence。Java 处理失败、空输出和非 JSON 情况，并对显式六位股票代码做确定性保护。

归一化话题生成稳定记忆 ID，与用户、会话组合；CONTINUE 复用当前窗口，SWITCH 进入另一个窗口，RETURN 回到已有窗口。独立查询同时供 RAG、长期记忆召回和 Planner 使用，业务消息保存后才推进活动话题。自然语言话题仍部分依赖模型，不能保证完全杜绝串话题。

入口：[ConversationContextService](../src/main/java/com/ljl/ai/memory/ConversationContextService.java)。验证：[ConversationQueryRewriteTest](../src/test/java/com/ljl/ai/service/ConversationQueryRewriteTest.java)。

### 22. 递归摘要如何避免丢消息和覆盖并发更新？

消息数或字符预算触发时，将较早一半消息与旧摘要合并，最近原文继续保留；切分点不能拆散 AI 工具调用和对应结果组。先生成摘要，检查非空与长度，再提交压缩。

Redis Lua 会比较生成摘要前的消息列表和旧摘要，匹配后才原子执行裁剪、新摘要写入与 TTL 刷新。生成失败不提交压缩，窗口或旧摘要变化则放弃本次提交。因此不能描述为“先删原文，失败再拿旧列表覆盖回去”。这能保护并发写入，但摘要本身仍是有损压缩。

入口：[ShortTermSummaryService](../src/main/java/com/ljl/ai/memory/ShortTermSummaryService.java)、[RedisChatMemoryStore](../src/main/java/com/ljl/ai/memory/RedisChatMemoryStore.java)。验证：[ShortTermSummaryServiceTest](../src/test/java/com/ljl/ai/memory/ShortTermSummaryServiceTest.java)、[RedisMemoryCompactionTest](../src/test/java/com/ljl/ai/memory/RedisMemoryCompactionTest.java)。

### 23. MongoDB、Redis、Milvus 为什么都需要？如何保证一致性和用户隔离？

MongoDB 保存业务消息、文档元数据、执行快照和决策；Redis 保存有 TTL 的模型窗口、摘要和话题状态；Milvus 负责知识与长期记忆的向量召回。数据职责不同，模型窗口不能代替完整业务历史。

知识文档通过状态标记、活动入库版本和失败补偿维护可见性。长期记忆先扩大共享向量候选池，再按 userId 和 MongoDB 启用状态过滤。当前没有跨 MongoDB/Milvus 的 ACID 事务，应用层用户过滤也不等于完整鉴权；生产化还需认证主体、存储层隔离和对账任务。

入口：[KnowledgeIngestionService](../src/main/java/com/ljl/ai/knowledge/KnowledgeIngestionService.java)、[KnowledgeService](../src/main/java/com/ljl/ai/knowledge/KnowledgeService.java)、[LongTermMemoryService](../src/main/java/com/ljl/ai/service/LongTermMemoryService.java)。

### 24. 长期记忆如何避免旧偏好复活或多实例并发覆盖？

用户消息先持久化，再以 user/session/message 幂等键进入 MongoDB 任务队列。worker 使用租约领取；会话已删除或归属变化时直接结束。候选携带原文证据和落盘时间，当前偏好槽位按用户和规范化维度条件更新，较旧来源不能取得发布权；历史记录保留替代关系和来源，删除会撤销会话来源。MongoDB 是读取事实来源，向量删除失败有补偿任务，缺失向量只对仍有效的记录补建。

模型提取受独立开关控制，输出必须通过类型、原文证据和用户归属校验。读取可按用户名单灰度；运行时检查任务积压、重试/死信、候选状态和向量补偿，离线固定样本覆盖长期偏好、临时要求和问句。可以说这些机制降低了错误记忆进入上下文的风险，不能在没有标注集和线上数据时宣称准确率或延迟提升。

入口：[LongTermMemoryService](../src/main/java/com/ljl/ai/service/LongTermMemoryService.java)、[MemoryJobWorker](../src/main/java/com/ljl/ai/service/MemoryJobWorker.java)。验证：[MemoryEvaluationTest](../src/test/java/com/ljl/ai/eval/MemoryEvaluationTest.java)、[UserMemoryConsolidationTest](../src/test/java/com/ljl/ai/service/UserMemoryConsolidationTest.java)。

### 25. 工具能并行调用吗？多个用户会排队等候吗？

深度投研中技术面、基本面、新闻和风险等没有数据依赖的工具分支可以并行执行，完成后再汇合并提交一次执行状态。这样不会让四个分支各自覆盖完整 MongoDB 快照；测试验证的是分支确实重叠执行，以及汇合后的结果完整性，不据此宣称固定的延迟收益。

对话入口则按会话串行：单个进程内以 `sessionId` 维护锁，同一用户的同一会话后续请求会等待前一轮完成，避免 Redis 窗口、话题状态和持久化消息交错。不同用户、或同一用户的不同会话没有共享锁，可以并行处理。当前不是全局持久队列，也没有跨实例分布式会话锁；多实例部署时需要 Redis 锁或按 `sessionId` 路由到固定消费者，外部工具并发还要受线程池、供应商限流和超时约束。

入口：[ChatService](../src/main/java/com/ljl/ai/service/ChatService.java)、[StockAnalysisWorkflow](../src/main/java/com/ljl/ai/workflow/StockAnalysisWorkflow.java)。验证：[ChatServiceOrchestrationTest](../src/test/java/com/ljl/ai/service/ChatServiceOrchestrationTest.java)、[StockAnalysisWorkflowTest](../src/test/java/com/ljl/ai/workflow/StockAnalysisWorkflowTest.java)。

## 排障、验证与设计取舍

### 24. 模型回答不符合预期，你怎么排查？

先把“不符合预期”分类成事实错误、漏项、引用错误、格式错误或表达差异，再固定问题、分析日期、模型配置和当时的数据快照。通过 traceId、executionId 关联链路，按顺序检查：

1. 原问题是否被错误补全，topicKey 和独立查询是否选错标的。
2. RAG 是否漏召回，文档版本过滤和上下文预算是否丢掉关键资料。
3. 计划和工具参数是否正确，工具快照是否通过 Schema、时点与证据检查。
4. 模型输入是否完整，Prompt 是否冲突，输出是否因长度或超时被截断。
5. 原始回答与最终展示是否一致，问题是否来自解析、Guard、降级或前端映射。

排除上游与后处理问题后，再调整 Prompt 或模型参数，一次改变一个变量，用回归样本比较。诊断正文只在受控环境采集；默认日志和 RunEvent 不能当作完整模型输入快照。

入口：[TracingChatLanguageModel](../src/main/java/com/ljl/ai/observability/TracingChatLanguageModel.java)、[WorkflowAnswerGenerator](../src/main/java/com/ljl/ai/workflow/WorkflowAnswerGenerator.java)。

### 25. 通用 Agent 怎样控制工具调用与低质量结果？

我会按“规划、执行、结果检查、有限重试、生成、输出校验”组织链路。工具白名单、参数类型、权限和预算由代码控制；工具返回先作为候选数据，检查来源、时间、完整性和业务相关性，再规范化为证据。数据不足时说明缺口，不能用无关内容凑答案。

本项目已经落地的是计划白名单、确定性工具映射、结构化返回、证据校验、任务重试上限和答案门禁。统一租户权限、全链路时间/成本预算、按错误类别退避重试等属于可继续完善的设计，不能把通用设计建议都说成已实现功能。

### 26. 你怎么证明这个项目有效？Agent Eval 高分说明什么？

我会分三层说明证据：单元/组件测试验证状态、边界和失败语义；显式集成测试验证真实存储和来源；标注数据与真实模型评测才用于回答生成质量问题。

当前 `mvn test` 默认离线，真实 MongoDB/Milvus 用 `*IT` 和 Profile 执行，新闻联网用例需显式启用。Agent Eval 使用 5 个固定样本与确定性适配器验证规划、话题、检索、证据和恢复契约，即使得分为 1.0，也不是线上模型准确率。CI 在 push/PR 时只执行后端打包和前端生产构建，后端跳过测试编译与执行；测试需手动运行，不能将构建成功描述为测试通过。

入口：[AgentEvalRunnerTest](../src/test/java/com/ljl/ai/eval/AgentEvalRunnerTest.java)、[评测样本](../src/test/resources/eval/agent-eval-cases.json)、[CI 配置](../.github/workflows/ci.yml)。

### 27. 这个项目最值得展开的难点是什么？下一步做什么？

可以选“并行执行后的可信恢复”来讲：分支不能修改共享状态，所以使用只读输入与 taskId 增量；分支不分别覆盖快照，而在汇合点 CAS 保存；工具成功但汇合前崩溃时，用工具记录恢复；恢复后还要重新校验当前证据，不能只信已完成状态。这条链路同时涉及并发、持久化、失败语义和模型输入边界。

下一步按需求排序：先建立真实标注集与延迟/成本基线，再评估重排、摘要质量和数据源覆盖；需要部署扩容时，再引入认证、分布式调度、持久事件流和对账。不要把增加模型角色数量当作效果改善的直接证据。

### 28. AI 应用上线后，你怎么判断应用的好坏？

我不会只看模型回答是否“像人”，而是先从业务目标倒推指标，并同时观察质量、可靠性、效率和安全性。上线前用固定标注集建立基线，覆盖正常请求、边界问题和故障场景；上线后通过灰度和版本对照持续监控，避免只看平均值。

质量层面按任务拆指标：检索看 Recall@K、有效来源覆盖和上下文命中；规划看合法计划率、工具选择与参数正确率；生成看事实正确率、引用准确率、任务完成率和人工采纳率。股票研究场景还要检查时点是否越界、证据是否支持结论，不能把语言流畅度当成答案正确率。

工程层面看请求成功率、降级率、超时率、P50/P95 延迟、单请求 Token 与外部工具成本，以及重试、队列积压和供应商错误分布。安全层面关注越权工具调用、提示注入、敏感信息泄露和不可追溯回答。最终仍要结合业务指标，例如用户是否完成分析、是否重复追问或人工纠错；业务指标变差时，再通过 traceId 下钻到检索、工具、模型和后处理定位原因。

这个项目当前已有离线契约测试、结构化证据校验、链路日志和降级状态，可证明关键边界是否工作；但还没有足够线上样本，不能直接宣称模型准确率或业务收益。完整上线方案还应补充人工标注集、线上反馈闭环、模型/Prompt 版本记录和灰度回滚阈值。

### 29. 怎么保证 AI 生成代码的模块化程度？

不能靠一句“请生成模块化代码”的 Prompt 保证，而要把模块边界变成模型必须遵守、工具可以检查的约束。我会先给出目录、分层职责、接口契约、依赖方向和禁止项，让 AI 先提出变更计划，再按小任务逐个实现；每个任务只允许修改明确范围，并要求通过现有测试和静态检查。

设计上重点控制四件事：按业务能力和变化原因拆模块；通过接口或明确 DTO 传递数据，避免共享可变状态；依赖只能由上层指向稳定抽象，禁止跨层直接访问存储或基础设施；把配置、模型调用和外部 API 隔离在适配层，使领域逻辑可以脱离真实模型测试。生成后再用代码评审检查类是否职责过多、参数是否泄漏内部结构、是否出现循环依赖、重复逻辑和为了复用而过度抽象。

在这个项目里，`ChatService` 只负责协调，上下文、执行、响应组装和持久化分别由独立服务承担；工作流节点通过不可变状态和字段明确的对象传递结果；模型接口、工具客户端和存储实现也有独立边界。测试按模块验证契约和失败语义，因此 AI 即使生成了能运行的代码，只要违反依赖方向、扩大修改范围或破坏测试，也不会被合并。

面试中可以概括为：**“AI 负责加速实现，模块化由人定义边界，再由接口、目录、测试、静态规则和代码评审共同兜底。”** 模块数量多不等于模块化好，最终要看职责是否单一、依赖是否清晰、变更能否局部完成以及模块能否独立测试。

## 表述边界速查

| 可以据当前实现说明 | 缺少依据或尚未实现的说法 |
| --- | --- |
| 四类工具分支并行，测试验证重叠执行与结果完整性 | 性能提升 XX%、达到某个线上 QPS/P95 |
| 应用层 CAS 快照、显式游标恢复和工具成功记录复用 | 原生任意节点恢复、通用 Exactly-once、重启自动续跑 |
| 结构化证据与输出规则拦截明确违规内容 | 完全消除幻觉、自动验证所有新闻和财务事实 |
| 多角色共享证据、并行分析后单次 Judge | 自由创建 Agent、任意调工具和相互通信 |
| 话题窗口、递归摘要与应用层用户过滤 | 无损摘要、100% 话题识别、完整多租户鉴权 |
| 补偿与活动版本控制文档可见性 | MongoDB 与 Milvus 强一致事务 |
| 离线契约评测与可复现测试入口 | fixture 分数等于模型准确率或投资收益 |

## 面试前的演示顺序

1. 从 `ChatService.chatInternal` 讲一轮请求，区分原文、独立查询、工作流答案与消息落库。
2. 展示 `StockAnalysisWorkflowTest` 与 `WorkflowNodeDeltaTest`，说明分支并行、增量合并和写入边界。
3. 展示 `WorkflowRunnerTest` 与 `WorkflowEvidenceBoundaryTest`，说明汇合前中断、成功工具复用和恢复后重验。
4. 展示 `RetrievalServiceTest`、`ConversationQueryRewriteTest` 和 `ShortTermSummaryServiceTest`，说明 BM25 独有命中、话题返回和摘要提交。
5. 基础设施与模型配置就绪后演示深度投研：接单、SSE 进度、来源、断线状态补偿。数据不足时说明触发了哪条规则。
6. 运行离线 Eval，解释固定样本能证明什么，再给出真实质量评测的后续方案。

默认验证命令见 [README](../README.md#测试与验证)。面试中只陈述实际执行过的测试和观察到的结果。
