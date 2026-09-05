# ISSUE-2026-09-05-agent-research-reliability 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 在保留现有 LangChain4j/LangGraph4j 架构的前提下，增加逐节点恢复、金融证据链、历史时点约束、运行事件、决策复盘、Agent Eval 和可选深度投研模式。

**架构：** 默认对话继续走现有受控 Planner 与确定性工具；深度投研通过异步接口启动，所有角色共享不可变 EvidencePack。工作流横向增加节点 Checkpoint、工具幂等、Claim–Evidence 校验与事件流，研究决策独立于聊天记忆持久化。

**技术栈：** Java 21、Spring Boot 3.3、LangChain4j、LangGraph4j、MongoDB、Redis、Milvus、JUnit 5、Mockito、React 19、Vite。

**相关文档：**
- 需求文档：`.ai/ISSUE-2026-09-05-agent-research-reliability/requirements.md`
- 设计文档：`.ai/ISSUE-2026-09-05-agent-research-reliability/design.md`
- 变更记录：`.ai/ISSUE-2026-09-05-agent-research-reliability/changelog.md`

**相关规范：**
- 仓库未提供 `yx-coder/AGENT.md`、架构规范或编码规范；以根目录 `AGENTS.md`、现有代码风格和上述设计文档为准。
- `src/main/resources/application.yml` 只作本地运行使用，本 Issue 禁止修改和提交。

**执行约束：**
- 开始实现前必须从 `main` 创建隔离分支 `issue/2026-09-05-agent-research-reliability`。
- 每个 Task 先把状态改为 `in_progress`，再执行 RED；没有匹配预期的 RED 不得修改生产代码。
- 每个 Task 完成后填写 Red/Green Evidence、更新 changelog、标记 `completed` 并独立提交。
- 新配置必须使用代码默认值或 `application.example.yml`，不得修改 `application.yml`。

---

### Task 1: 建立 AnalysisContext 与请求兼容边界

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=AnalysisContextResolverTest test'` 失败；测试编译阶段报告 `AnalysisContextResolver` 不存在，与预期缺少新领域类型一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=AnalysisContextResolverTest test'` 通过（3 tests）；验证旧请求缺省值、显式日期/深度模式与未来日期拒绝。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/research/AnalysisContext.java`
- Create: `src/main/java/com/ljl/ai/research/AnalysisContextResolver.java`
- Modify: `src/main/java/com/ljl/ai/model/dto/ChatRequest.java`
- Test: `src/test/java/com/ljl/ai/research/AnalysisContextResolverTest.java`

**步骤 0：开始任务前更新状态**
- 将本 Task 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 覆盖旧请求缺省为 `STANDARD + 当前日期`、ISO 日期解析、未来日期拒绝、显式 `DEEP`、executionId/traceId/userId/sessionId 原样保留。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=AnalysisContextResolverTest test'`
- Expected: FAIL，`AnalysisContext` / `AnalysisContextResolver` 尚不存在。

**步骤 3：最小实现**
- `AnalysisContext` 使用不可变 record，内含 `ResearchMode { STANDARD, DEEP }`。
- `ChatRequest` 增加可选 `analysisDate`、`researchMode`，不改变旧字段默认行为。
- Resolver 负责唯一的日期与模式解析入口，不从系统属性读取业务参数。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=AnalysisContextResolverTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 填写 Red/Green Evidence，更新 changelog，状态改为 `completed`。

**步骤 6：提交**
- Commit: `feat: 建立投研分析上下文`

---

### Task 2: 建立金融事实与证据包领域模型

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=FinancialFactTest,ExecutionTaskTest test'` 失败；测试编译阶段报告 `FinancialFact` 不存在，与预期缺少事实模型和任务证据接口一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=FinancialFactTest,ExecutionTaskTest test'` 通过（6 tests）；验证稳定 evidenceId、嵌套集合不可变及成功重试证据追加去重。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/research/FinancialFact.java`
- Create: `src/main/java/com/ljl/ai/research/EvidencePack.java`
- Modify: `src/main/java/com/ljl/ai/workflow/ExecutionTask.java`
- Test: `src/test/java/com/ljl/ai/research/FinancialFactTest.java`
- Test: `src/test/java/com/ljl/ai/workflow/ExecutionTaskTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 验证 evidenceId 稳定生成、temporalStatus 枚举、EvidencePack 集合不可变、ExecutionTask 成功结果可携带证据且重试历史不覆盖旧证据。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=FinancialFactTest,ExecutionTaskTest test'`
- Expected: FAIL，新领域类型和 ExecutionTask 证据字段不存在。

**步骤 3：最小实现**
- `FinancialFact` 包含设计文档定义的来源、时间、公式、快照和状态字段。
- `EvidencePack` 保存 AnalysisContext、分类证据、缺失项、dataAsOf、evidenceHash，构造时防御性复制。
- `ExecutionTask` 增加 `List<FinancialFact> evidence` 和追加方法。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=FinancialFactTest,ExecutionTaskTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新 tasks/changelog 并标记完成。

**步骤 6：提交**
- Commit: `feat: 定义金融事实与证据包`

---

### Task 3: 为数据客户端增加 point-in-time 过滤

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=PointInTimeDataContractTest,NewsSearchClientTest test'` 失败；报告缺少 `filterBarsAsOf`、`FinancialSnapshot/selectSnapshotAsOf` 和新闻时间过滤/状态接口，与预期一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=PointInTimeDataContractTest,NewsSearchClientTest test'` 通过（5 tests）；验证日 K 截止、财报按披露日选择、未来新闻剔除及未知时间显式标记。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/client/MarketDataClient.java`
- Modify: `src/main/java/com/ljl/ai/client/FinancialDataClient.java`
- Modify: `src/main/java/com/ljl/ai/client/NewsSearchClient.java`
- Test: `src/test/java/com/ljl/ai/client/PointInTimeDataContractTest.java`
- Test: `src/test/java/com/ljl/ai/client/NewsSearchClientTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 固定伪造响应，验证日 K、财报披露时间和新闻发布时间均不会越过 analysisDate；未知披露时间显式返回 UNKNOWN，而不是伪造日期。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=PointInTimeDataContractTest,NewsSearchClientTest test'`
- Expected: FAIL，客户端尚无 analysisDate 契约或未来数据过滤。

**步骤 3：最小实现**
- 新增日期感知方法并保留旧方法委托到当前日期，维持兼容。
- 历史行情从日 K 选取 `date <= analysisDate` 的最后记录。
- 新闻解析 publishedAt，无法解析时标为未知并由调用方决定是否进入验证证据。
- 财报同时返回报告期和可用披露日期；数据源缺失披露日期时保留 UNKNOWN。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=PointInTimeDataContractTest,NewsSearchClientTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新文档状态和证据。

**步骤 6：提交**
- Commit: `feat: 增加金融数据历史时点约束`

---

### Task 4: 让行情与技术工具消费 AnalysisContext

**状态：** completed

**Red Evidence：** 修正测试中 `@Tool.value()` 为数组的断言后，`zsh -ic 'jdk21 && mvn -q -Dtest=MarketDataToolTest,StockAnalysisTaskExecutorTest test'` 失败；仅报告缺少 `getQuote(symbol, AnalysisContext)`、技术分析上下文重载及 Executor 上下文入口，与预期一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=MarketDataToolTest,StockAnalysisTaskExecutorTest test'` 通过（7 tests）；验证历史行情走截止日 K 线、上下文同实例传递、技术结果包含截止日且工具描述只声明真实计算指标。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/tools/MarketDataTool.java`
- Modify: `src/main/java/com/ljl/ai/tools/TechnicalAnalysisTool.java`
- Modify: `src/main/java/com/ljl/ai/workflow/StockAnalysisTaskExecutor.java`
- Test: `src/test/java/com/ljl/ai/tools/MarketDataToolTest.java`
- Test: `src/test/java/com/ljl/ai/workflow/StockAnalysisTaskExecutorTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 验证历史日期走 as-of 行情和截止该日的 K 线；Executor 把同一个 AnalysisContext 传给工具；技术工具描述只宣称实际计算的指标。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=MarketDataToolTest,StockAnalysisTaskExecutorTest test'`
- Expected: FAIL，Executor/工具尚未接收 AnalysisContext。

**步骤 3：最小实现**
- 为工作流调用增加日期感知入口，保留原 `@Tool` 方法兼容自主工具调用。
- 技术结果明确数据截止日；不得使用截止日之后 K 线。
- 修正当前“已计算 MACD/RSI/KDJ/布林带”的过度工具描述，除非本 Task 同时提供真实计算。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=MarketDataToolTest,StockAnalysisTaskExecutorTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新证据和状态。

**步骤 6：提交**
- Commit: `feat: 统一行情与技术分析时点`

---

### Task 5: 让财务与新闻工具消费 AnalysisContext

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=FinancialAnalysisToolTest,StockAnalysisTaskExecutorTest test'` 失败；测试编译阶段仅报告财务/新闻工具缺少 `AnalysisContext` 重载，与预期一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=FinancialAnalysisToolTest,StockAnalysisTaskExecutorTest test'` 通过（7 tests）；验证财报披露/来源/时点状态输出、无历史数据不回退，以及新闻保留 URL/source/publishedAt/temporalStatus。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/tools/FinancialAnalysisTool.java`
- Modify: `src/main/java/com/ljl/ai/tools/NewsRagTool.java`
- Modify: `src/main/java/com/ljl/ai/workflow/StockAnalysisTaskExecutor.java`
- Test: `src/test/java/com/ljl/ai/tools/FinancialAnalysisToolTest.java`
- Test: `src/test/java/com/ljl/ai/workflow/StockAnalysisTaskExecutorTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 验证财报披露日与新闻发布时间不晚于 analysisDate；无合格数据返回明确缺失，不回退未来内容。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=FinancialAnalysisToolTest,StockAnalysisTaskExecutorTest test'`
- Expected: FAIL，日期感知入口尚未完整覆盖财务/新闻。

**步骤 3：最小实现**
- 工作流入口使用日期感知客户端；原 Tool Calling 方法保持兼容并默认当前日期。
- 新闻结果保留 source、URL、publishedAt 和 temporalStatus。
- 财报结果保留 period、publishedAt/source 和 UNKNOWN 状态。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=FinancialAnalysisToolTest,StockAnalysisTaskExecutorTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新证据和状态。

**步骤 6：提交**
- Commit: `feat: 统一财务与新闻分析时点`

---

### Task 6: 将工具结果映射并组装为 EvidencePack

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=EvidencePackBuilderTest,StockAnalysisTaskNodeTest test'` 失败；测试编译阶段报告 `EvidencePackBuilder` 不存在，与预期缺少映射器及执行状态证据包接口一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=EvidencePackBuilderTest,StockAnalysisTaskNodeTest test'` 通过（6 tests）；验证真实字段映射、分类、去重、未来事实排除、稳定 evidenceHash、缺失/失败记录及节点刷新 EvidencePack。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/research/EvidencePackBuilder.java`
- Modify: `src/main/java/com/ljl/ai/workflow/StockAnalysisTaskNode.java`
- Modify: `src/main/java/com/ljl/ai/workflow/ExecutionState.java`
- Test: `src/test/java/com/ljl/ai/research/EvidencePackBuilderTest.java`
- Test: `src/test/java/com/ljl/ai/workflow/StockAnalysisTaskNodeTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 覆盖 StockQuote 数值事实、技术/财务/新闻文本证据、来源时间状态、重复 evidenceId 去重、未来事实拒绝、缺失项和稳定 evidenceHash。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=EvidencePackBuilderTest,StockAnalysisTaskNodeTest test'`
- Expected: FAIL，Builder 尚不存在，任务节点和执行状态未保存证据包。

**步骤 3：最小实现**
- Builder 只映射工具真实返回字段，不让模型补数值，并按分类和 evidenceId 去重。
- REJECTED 不进入可引用证据，UNKNOWN 单独标记。
- 节点在完成任务前同时保存原始结果与证据，并刷新 ExecutionState 中的不可变 EvidencePack。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=EvidencePackBuilderTest,StockAnalysisTaskNodeTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新证据和状态。

**步骤 6：提交**
- Commit: `feat: 构建不可变投研证据包`

---

### Task 7: 实现逐节点 Checkpoint 与图版本保护

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=StockAnalysisWorkflowTest,WorkflowRunnerTest test'` 失败；测试编译阶段报告缺少 CheckpointCallback、逐节点 run 重载、graphVersion/planHash/lastCompletedNode 和 checkpointCompleted，与预期一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=StockAnalysisWorkflowTest,WorkflowRunnerTest test'` 通过（10 tests）；验证 INIT 先保存再执行任务、逐节点与 CRITIC 路由检查点、CAS 冲突停止推进，以及 graphVersion/planHash 不兼容拒绝恢复。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/workflow/ExecutionState.java`
- Modify: `src/main/java/com/ljl/ai/workflow/StockAnalysisWorkflow.java`
- Modify: `src/main/java/com/ljl/ai/workflow/WorkflowRunner.java`
- Test: `src/test/java/com/ljl/ai/workflow/StockAnalysisWorkflowTest.java`
- Test: `src/test/java/com/ljl/ai/workflow/WorkflowRunnerTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 记录 Store 调用顺序，验证节点成功后立即保存；保存失败不进入下一节点；恢复时 graphVersion/planHash 不匹配返回稳定错误；已完成任务保持跳过。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=StockAnalysisWorkflowTest,WorkflowRunnerTest test'`
- Expected: FAIL，当前只在工作流首尾保存。

**步骤 3：最小实现**
- ExecutionState 增加 graphVersion、planHash、lastCompletedNode、eventSequence。
- Workflow 的节点包装器接收 checkpoint callback，动作成功后 CAS 保存，保存成功才返回节点结果。
- CRITIC 路由在返回 Command 前保存；Runner 恢复前校验版本与计划摘要。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=StockAnalysisWorkflowTest,WorkflowRunnerTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新证据和状态。

**步骤 6：提交**
- Commit: `feat: 持久化工作流节点检查点`

---

### Task 8: 建立工具执行幂等存储

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=MongoToolExecutionStoreTest test'` 失败；测试编译阶段报告 `ToolExecutionRecord` 与 `MongoToolExecutionStore` 不存在，与预期一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=MongoToolExecutionStoreTest test'` 通过（4 tests）；验证复合幂等键、STARTED 条件迁移、结果/证据快照、重复成功幂等及成功记录冲突保护。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/workflow/ToolExecutionRecord.java`
- Create: `src/main/java/com/ljl/ai/workflow/ToolExecutionStore.java`
- Create: `src/main/java/com/ljl/ai/workflow/MongoToolExecutionStore.java`
- Test: `src/test/java/com/ljl/ai/workflow/MongoToolExecutionStoreTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 验证唯一 id 为 executionId/taskId/attempt，STARTED→SUCCEEDED/FAILED 合法迁移，重复成功写入幂等，冲突不覆盖成功结果。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=MongoToolExecutionStoreTest test'`
- Expected: FAIL，幂等记录和存储尚不存在。

**步骤 3：最小实现**
- 使用 MongoTemplate 条件更新；结果快照和证据随 SUCCEEDED 保存。
- Store 接口提供 begin/find/complete/fail，不暴露任意状态覆盖。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=MongoToolExecutionStoreTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新证据和状态。

**步骤 6：提交**
- Commit: `feat: 增加工具执行幂等记录`

---

### Task 9: 将工具幂等接入任务节点

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=StockAnalysisTaskNodeTest,ExecutionTaskTest test'` 失败；测试编译阶段报告节点缺少 ToolExecutionStore 构造入口、ExecutionTask 缺少恢复 attempt 方法，与预期一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=StockAnalysisTaskNodeTest,ExecutionTaskTest test'` 通过（12 tests）；验证成功记录零调用恢复、STARTED 只读工具使用下一 attempt、失败重试上限、原始结果/证据一致恢复及完成记录失败不误标完成。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/workflow/StockAnalysisTaskNode.java`
- Modify: `src/main/java/com/ljl/ai/workflow/ExecutionTask.java`
- Modify: `src/main/java/com/ljl/ai/workflow/WorkflowRetryPolicy.java`
- Test: `src/test/java/com/ljl/ai/workflow/StockAnalysisTaskNodeTest.java`
- Test: `src/test/java/com/ljl/ai/workflow/ExecutionTaskTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 验证 SUCCEEDED 记录直接恢复结果且 executor 零调用；FAILED 遵循重试上限；STARTED 只读工具可产生下一 attempt；恢复证据与原始结果一致。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=StockAnalysisTaskNodeTest,ExecutionTaskTest test'`
- Expected: FAIL，节点尚未查询 ToolExecutionStore。

**步骤 3：最小实现**
- 节点调用顺序固定为查询成功记录、begin、执行、映射证据、complete、更新任务。
- Mongo 完成记录失败时任务不能进入 COMPLETED。
- 仅当前四类只读工具允许 STARTED 后重试，代码中显式白名单。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=StockAnalysisTaskNodeTest,ExecutionTaskTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新证据和状态。

**步骤 6：提交**
- Commit: `feat: 恢复时复用成功工具结果`

---

### Task 10: 建立类型化 RunEvent 发布器

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=InMemoryRunEventPublisherTest test'` 失败；测试编译阶段报告 `RunEvent`、`RunEventPublisher` 与 `InMemoryRunEventPublisher` 不存在，与预期缺少类型化事件发布能力一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=InMemoryRunEventPublisherTest test'` 通过（3 tests）；验证 executionId 独立连续序号、每次执行的有界回放、取消订阅后继续发布，以及事件结构不暴露 Prompt/响应正文且摘要长度受限。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/observability/RunEvent.java`
- Create: `src/main/java/com/ljl/ai/observability/RunEventPublisher.java`
- Create: `src/main/java/com/ljl/ai/observability/InMemoryRunEventPublisher.java`
- Test: `src/test/java/com/ljl/ai/observability/InMemoryRunEventPublisherTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 验证每个 executionId 独立递增 sequence、有界回放、订阅取消不影响发布、事件摘要不接受完整 Prompt/响应字段。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=InMemoryRunEventPublisherTest test'`
- Expected: FAIL，事件类型和发布器尚不存在。

**步骤 3：最小实现**
- RunEvent 使用不可变 record 和固定 EventType。
- Publisher 提供 publish、snapshot、subscribe/unsubscribe；每个 executionId 有界保存最近事件。
- 不引入 Reactor，仅使用 JDK 并发容器和 Spring MVC `SseEmitter` 适配所需回调。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=InMemoryRunEventPublisherTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新证据和状态。

**步骤 6：提交**
- Commit: `feat: 增加类型化工作流事件`

---

### Task 11: 在工作流和工具节点发布 RunEvent

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=WorkflowRunnerTest,StockAnalysisTaskNodeTest test'` 失败；测试编译阶段报告 `WorkflowRunner`、`StockAnalysisWorkflow` 和 `StockAnalysisTaskNode` 均缺少发布器注入构造入口，与预期执行链尚未发布 RunEvent 一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=WorkflowRunnerTest,StockAnalysisTaskNodeTest test'` 通过（13 tests）；验证 PLAN/NODE/TOOL/终态事件及连续序号，检查点失败时不发布 NODE_COMPLETED 但保留 WORKFLOW_FAILED，工具成功、业务失败和异常事件均只含受控元数据且 eventSequence 回写执行状态。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/workflow/WorkflowRunner.java`
- Modify: `src/main/java/com/ljl/ai/workflow/StockAnalysisWorkflow.java`
- Modify: `src/main/java/com/ljl/ai/workflow/StockAnalysisTaskNode.java`
- Test: `src/test/java/com/ljl/ai/workflow/WorkflowRunnerTest.java`
- Test: `src/test/java/com/ljl/ai/workflow/StockAnalysisTaskNodeTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 验证 PLAN/NODE/TOOL/RETRY/COMPLETED/FAILED 事件顺序、同 executionId 连续序号、异常路径仍有终态事件且不包含工具正文。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=WorkflowRunnerTest,StockAnalysisTaskNodeTest test'`
- Expected: FAIL，执行链尚未发布事件。

**步骤 3：最小实现**
- 在状态变化完成并成功 checkpoint 后发布完成事件。
- 工具事件只记录 tool、状态、耗时和 errorCode；不记录参数密钥或结果正文。
- eventSequence 同步回写 ExecutionState。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=WorkflowRunnerTest,StockAnalysisTaskNodeTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新证据和状态。

**步骤 6：提交**
- Commit: `feat: 发布投研工作流运行事件`

---

### Task 12: 支持预分配 executionId 的异步深度研究

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchExecutionServiceTest,ChatServiceWorkflowTest test'` 失败；测试编译阶段报告 `ResearchExecutionResponse` 不存在，与预期缺少异步启动契约和服务一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchExecutionServiceTest,ChatServiceWorkflowTest test'` 通过（9 tests）；验证异步入口预分配 executionId/sessionId、仅接受 DEEP、ChatService 复用指定 executionId、有界队列稳定拒绝、线程池关闭，以及后台异常以脱敏终态事件和同步 eventSequence 的 FAILED 状态落盘。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/service/ResearchExecutionService.java`
- Create: `src/main/java/com/ljl/ai/model/dto/ResearchExecutionResponse.java`
- Modify: `src/main/java/com/ljl/ai/service/ChatService.java`
- Test: `src/test/java/com/ljl/ai/service/ResearchExecutionServiceTest.java`
- Test: `src/test/java/com/ljl/ai/service/ChatServiceWorkflowTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 验证异步服务先返回 executionId/sessionId 再执行；只接受 DEEP；ChatService 使用预分配 executionId；有界队列拒绝时返回稳定错误；关闭时释放线程池。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchExecutionServiceTest,ChatServiceWorkflowTest test'`
- Expected: FAIL，异步服务和 ChatService 重载尚不存在。

**步骤 3：最小实现**
- 使用代码配置的有界 `ThreadPoolExecutor`，不依赖 application.yml。
- `ChatService.chat(request, executionId)` 仅供内部使用；公开同步行为不变。
- 异步异常写入工作流失败状态并发布 WORKFLOW_FAILED。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchExecutionServiceTest,ChatServiceWorkflowTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新证据和状态。

**步骤 6：提交**
- Commit: `feat: 异步启动深度投研任务`

---

### Task 13: 提供执行状态与 SSE 接口

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchExecutionControllerTest,InMemoryRunEventPublisherTest test'` 失败；测试编译阶段报告缺少 `ResearchExecutionController`、`findOwned` 和 `subscribeAfter`，与预期尚无状态/SSE 适配及所有权查询一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchExecutionControllerTest,InMemoryRunEventPublisherTest test'` 通过（8 tests）；验证 POST 返回 202 与预分配句柄、状态仅对 execution 所属 userId 可见、SSE 按序回放并在终态完成，以及基于 cursor 的原子补发、去重和取消订阅。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/controller/ResearchExecutionController.java`
- Modify: `src/main/java/com/ljl/ai/observability/InMemoryRunEventPublisher.java`
- Modify: `src/main/java/com/ljl/ai/service/ResearchExecutionService.java`
- Test: `src/test/java/com/ljl/ai/controller/ResearchExecutionControllerTest.java`
- Test: `src/test/java/com/ljl/ai/observability/InMemoryRunEventPublisherTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 覆盖 POST 异步启动返回 202、GET 状态校验 userId、GET SSE 先回放快照再订阅、断连只移除订阅、终态自动 complete。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchExecutionControllerTest,InMemoryRunEventPublisherTest test'`
- Expected: FAIL，Controller/SSE 适配尚不存在。

**步骤 3：最小实现**
- 路由使用 `/api/research/executions`、`/{executionId}`、`/{executionId}/events`。
- 状态查询从 ExecutionStateStore 获取并执行 userId 所有权校验。
- SseEmitter timeout/completion/error 只注销 listener。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchExecutionControllerTest,InMemoryRunEventPublisherTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新证据和状态。

**步骤 6：提交**
- Commit: `feat: 暴露投研执行状态与事件流`

---

### Task 14: 建立 Claim–Evidence 校验并接入答案生成

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ClaimEvidenceGuardTest,WorkflowAnswerGeneratorTest test'` 失败；测试编译阶段报告 `ClaimEvidenceGuard` 不存在，与预期缺少证据门禁及生成链路接入一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ClaimEvidenceGuardTest,WorkflowAnswerGeneratorTest test'` 通过（10 tests）；验证当前 EvidencePack 合法引用、未知/跨包 ID 拒绝、无引用数值拒绝、晚于 dataAsOf 日期拒绝，以及证据校验后独立执行 Markdown Guard、一次重写和确定性降级。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/research/ClaimEvidenceGuard.java`
- Modify: `src/main/java/com/ljl/ai/workflow/WorkflowAnswerGenerator.java`
- Modify: `src/main/java/com/ljl/ai/agent/WorkflowAnswerAssistant.java`
- Test: `src/test/java/com/ljl/ai/research/ClaimEvidenceGuardTest.java`
- Test: `src/test/java/com/ljl/ai/workflow/WorkflowAnswerGeneratorTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 覆盖合法 evidenceId、未知 ID、跨 EvidencePack ID、无来源数字、日期晚于 dataAsOf、一次重写和确定性降级；确认 Markdown Guard 仍独立执行。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ClaimEvidenceGuardTest,WorkflowAnswerGeneratorTest test'`
- Expected: FAIL，证据门禁不存在且未接入生成链路。

**步骤 3：最小实现**
- Guard 返回稳定 Reason 枚举和缺失 evidenceIds，不进行模糊事实猜测；答案 Prompt 要求关键结论使用现有 evidenceId。
- WorkflowAnswerGenerator 顺序为证据校验、Markdown 校验、一次重写、降级。
- 旧关键词 `factCheck` 保持未接入状态并在最终文档标记为兼容代码，不用它冒充事实验证。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ClaimEvidenceGuardTest,WorkflowAnswerGeneratorTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新证据和状态。

**步骤 6：提交**
- Commit: `feat: 校验回答与金融证据映射`

---

### Task 15: 定义深度投研角色与结构化结论

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=DeepResearchServiceTest,DeepResearchAssistantContractTest test'` 失败；测试编译阶段报告 `DeepResearchAssistant` 不存在，与预期缺少固定角色契约、结构化结论和编排服务一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=DeepResearchServiceTest,DeepResearchAssistantContractTest test'` 通过（6 tests）；验证六角色固定顺序且各调用一次、共享同一预算证据、契约无工具/无 MemoryId、Judge JSON 解析、结论字段边界、跨包 evidenceId 拒绝、单角色失败继续及 Judge 失败确定性降级。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/research/ResearchConclusion.java`
- Create: `src/main/java/com/ljl/ai/agent/DeepResearchAssistant.java`
- Create: `src/main/java/com/ljl/ai/research/DeepResearchService.java`
- Test: `src/test/java/com/ljl/ai/research/DeepResearchServiceTest.java`
- Test: `src/test/java/com/ljl/ai/agent/DeepResearchAssistantContractTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 验证角色固定顺序、每个角色一次调用、共享同一 EvidencePack、角色无工具/无 MemoryId、Judge JSON 解析、未知 evidenceId 拒绝、单角色失败可继续、Judge 失败返回降级标记。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=DeepResearchServiceTest,DeepResearchAssistantContractTest test'`
- Expected: FAIL，深度研究接口与服务尚不存在。

**步骤 3：最小实现**
- Assistant 提供 fundamental/technical/news/bull/bear/risk/judge 方法，全部只接受预算后的证据文本和上游摘要。
- Service 不实现自由群聊；固定 DAG 和调用上限。
- ResearchConclusion 使用不可变结构并校验 rating/confidence/evidenceIds/dataAsOf。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=DeepResearchServiceTest,DeepResearchAssistantContractTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新证据和状态。

**步骤 6：提交**
- Commit: `feat: 增加受控多角色投研审议`

---

### Task 16: 将深度投研分支接入工作流

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=StockAnalysisWorkflowTest,WorkflowAnswerGeneratorTest test'` 失败；测试编译阶段报告 `StockAnalysisWorkflow` 缺少深度投研服务构造入口，`ExecutionState` 缺少 `ResearchConclusion` 读写方法，与预期尚未接入深度投研分支和结构化结论状态一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=StockAnalysisWorkflowTest,WorkflowAnswerGeneratorTest test'` 通过（16 tests）；额外执行 `DeepResearchServiceTest,DeepResearchAssistantContractTest,AgentConfigToolSelectionTest` 通过，验证 STANDARD/DEEP 条件路由、深度节点 checkpoint 与事件、结构化结论确定性呈现、Judge 失败回退标准答案链路，以及深度 Assistant 不挂载工具和会话记忆。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/agent/AgentConfig.java`
- Modify: `src/main/java/com/ljl/ai/research/DeepResearchService.java`
- Modify: `src/main/java/com/ljl/ai/workflow/ExecutionState.java`
- Modify: `src/main/java/com/ljl/ai/workflow/StockAnalysisWorkflow.java`
- Modify: `src/main/java/com/ljl/ai/workflow/WorkflowAnswerGenerator.java`
- Test: `src/test/java/com/ljl/ai/workflow/StockAnalysisWorkflowTest.java`
- Test: `src/test/java/com/ljl/ai/workflow/WorkflowAnswerGeneratorTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- STANDARD 直接进入 ANSWER；DEEP 在 EvidencePack 后进入 DEEP_RESEARCH；角色失败降级；Judge 成功使用结构化结论；节点事件和 checkpoint 完整。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=StockAnalysisWorkflowTest,WorkflowAnswerGeneratorTest test'`
- Expected: FAIL，图中尚无深度研究条件分支。

**步骤 3：最小实现**
- AgentConfig 构建无工具、无记忆 DeepResearchAssistant。
- 图增加 EVIDENCE_PACK 与 DEEP_RESEARCH 节点，按 AnalysisContext.researchMode 路由。
- Judge 结论通过 ClaimEvidenceGuard 后由确定性 Presenter 转 Markdown；失败沿用标准答案链路。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=StockAnalysisWorkflowTest,WorkflowAnswerGeneratorTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新证据和状态。

**步骤 6：提交**
- Commit: `feat: 接入可选深度投研工作流`

---

### Task 17: 建立研究决策与后验复盘服务

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchDecisionServiceTest,DecisionReviewServiceTest test'` 失败；测试编译阶段报告 `ResearchDecision`（以及依赖它的决策保存、复盘服务）不存在，与预期尚无独立投研决策模型和后验收益闭环一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchDecisionServiceTest,DecisionReviewServiceTest test'` 通过（7 tests）；验证 executionId 幂等保存、userId/symbol/状态/可见时间联合隔离、1/5/20 交易日 BigDecimal 收益、相对沪深 300 ETF 基准收益、未到期跳过、基准缺失降级、重复复盘不重写，以及历史 analysisDate 不召回未来 outcome。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/research/ResearchDecision.java`
- Create: `src/main/java/com/ljl/ai/research/ResearchDecisionService.java`
- Create: `src/main/java/com/ljl/ai/research/DecisionReviewService.java`
- Test: `src/test/java/com/ljl/ai/research/ResearchDecisionServiceTest.java`
- Test: `src/test/java/com/ljl/ai/research/DecisionReviewServiceTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 覆盖 userId/symbol 隔离、证据哈希、1/5/20 日收益、相对基准收益、未到期不评估、历史 analysisDate 不召回未来 outcome、重复评估幂等。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchDecisionServiceTest,DecisionReviewServiceTest test'`
- Expected: FAIL，决策模型和复盘服务尚不存在。

**步骤 3：最小实现**
- MongoTemplate 查询必须同时包含 userId、symbol、状态和时间上界。
- 收益计算使用 BigDecimal 和固定舍入规则；基准缺失时保留标的收益并明确缺失。
- reflection 使用确定性模板，不调用 LLM，不混入聊天/长期偏好记忆。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchDecisionServiceTest,DecisionReviewServiceTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新证据和状态。

**步骤 6：提交**
- Commit: `feat: 保存并复盘历史投研决策`

---

### Task 18: 将决策复盘接入对话与深度研究

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ChatServiceWorkflowTest,DeepResearchServiceTest test'` 失败；测试编译阶段报告缺少带 ChatRequest 的执行状态构建入口、ExecutionState 决策复盘字段、业务消息后决策保存入口，以及 DeepResearchService 的复盘输入重载，与预期主链路尚未接入一致。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ChatServiceWorkflowTest,DeepResearchServiceTest test'` 通过（14 tests）；验证请求的 DEEP/analysisDate 进入 ExecutionState、复盘刷新失败不阻断主链路、只注入同用户同标的且在分析时点已可见的完成复盘、STANDARD 不保存评级、助手业务消息未保存时不写决策，以及决策持久化失败 best-effort 降级。整库 `zsh -ic 'jdk21 && mvn -q test'` 通过（247 tests，0 failures/errors/skipped）。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/service/ChatService.java`
- Modify: `src/main/java/com/ljl/ai/research/DeepResearchService.java`
- Modify: `src/main/java/com/ljl/ai/workflow/ExecutionState.java`
- Modify: `src/main/java/com/ljl/ai/workflow/StockAnalysisWorkflow.java`
- Test: `src/test/java/com/ljl/ai/service/ChatServiceWorkflowTest.java`
- Test: `src/test/java/com/ljl/ai/research/DeepResearchServiceTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 验证深度研究成功后保存决策；下次同用户同标的只注入 analysisDate 前可用复盘；复盘失败不阻断本轮；STANDARD 不强制保存评级。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ChatServiceWorkflowTest,DeepResearchServiceTest test'`
- Expected: FAIL，决策服务尚未进入主链路。

**步骤 3：最小实现**
- ChatService 在建立 EvidencePack 前 best-effort 刷新到期复盘并读取合格记录。
- DeepResearchService 将复盘作为独立、带日期标签的参考区，不允许覆盖本轮事实。
- 仅在 ResearchConclusion 和业务消息均成功保存后写 ResearchDecision。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ChatServiceWorkflowTest,DeepResearchServiceTest test'`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新证据和状态。

**步骤 6：提交**
- Commit: `feat: 在投研流程中使用决策复盘`

---

### Task 19: 建立离线 Agent Eval 与基线报告

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Create: `src/test/java/com/ljl/ai/eval/AgentEvalRunner.java`
- Create: `src/test/java/com/ljl/ai/eval/AgentEvalRunnerTest.java`
- Create: `src/test/resources/eval/agent-eval-cases.json`
- Create: `src/test/resources/eval/README.md`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 固定样本覆盖 Planner、Topic Routing、RAG、Evidence 和 Recovery；验证 accuracy、Recall@K、nDCG、引用覆盖、数字一致性、平均延迟和调用次数计算。

**步骤 2：运行 RED**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=AgentEvalRunnerTest test'`
- Expected: FAIL，评测 Runner/样本尚不存在。

**步骤 3：最小实现**
- Runner 接受函数式适配器，默认测试使用固定返回值，不调用网络或模型。
- 报告输出稳定 JSON；零样本、重复 caseId 和非法期望值明确失败。
- 在资源 README 记录在线评测应使用显式 Profile，禁止默认 CI 访问外部服务。

**步骤 4：运行 GREEN**
- Run: `zsh -ic 'jdk21 && mvn -q -Dtest=AgentEvalRunnerTest test'`
- Expected: PASS，并生成测试内可断言的结构化基线。

**步骤 5：回写证据并完成**
- 把实际基线指标写入 changelog；状态改为完成。

**步骤 6：提交**
- Commit: `test: 建立 Agent 离线评测基线`

---

### Task 20: 建立前端深度投研 API 与事件客户端

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Modify: `frontend/package.json`
- Create: `frontend/src/researchExecution.js`
- Test: `frontend/src/researchExecution.test.js`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 使用 Node 内置 test runner，覆盖异步启动请求、executionId 校验、RunEvent 解析、终态识别、EventSource 关闭与状态补偿请求。

**步骤 2：运行 RED**
- Run: `cd frontend && npm test`
- Expected: FAIL，测试脚本与 researchExecution 客户端尚不存在。

**步骤 3：最小实现**
- package.json 使用 `node --test`，不为纯函数测试额外引入大型测试框架。
- 客户端封装 start/getStatus/subscribe，严格校验 executionId 和事件 JSON。
- EventSource 失败后关闭旧连接并执行状态补偿；调用方负责决定是否重连。

**步骤 4：运行 GREEN**
- Run: `cd frontend && npm test && npm run build`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新证据和状态。

**步骤 6：提交**
- Commit: `feat: 封装深度投研事件客户端`

---

### Task 21: 在前端突出深度投研模式与运行进度

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Modify: `frontend/src/App.jsx`
- Modify: `frontend/src/styles.css`
- Modify: `frontend/src/researchExecution.test.js`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：编写失败测试**
- 增加 UI 状态纯函数测试，覆盖标准/深度模式请求分流、阶段时间线、失败重连提示和终态结果映射。

**步骤 2：运行 RED**
- Run: `cd frontend && npm test`
- Expected: FAIL，UI 所需状态映射尚不存在。

**步骤 3：最小实现**
- 增加醒目的“标准分析 / 深度投研”选择，不隐藏在高级设置。
- 深度模式启动后展示 executionId、阶段时间线、重试和数据缺失，不显示内部 Prompt/推理正文。
- 组件卸载时关闭 EventSource；断线显示状态补偿结果和手动重连入口。

**步骤 4：运行 GREEN**
- Run: `cd frontend && npm test && npm run build`
- Expected: PASS。

**步骤 5：回写证据并完成**
- 更新证据和状态。

**步骤 6：提交**
- Commit: `feat: 展示深度投研执行进度`

---

### Task 22: 更新 README、面试材料并完成全量验证

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Modify: `README.md`
- Modify: `docs/resume-and-interview.md`
- Modify: `.ai/ISSUE-2026-09-05-agent-research-reliability/changelog.md`
- Test: `src/test/java/com/ljl/ai/controller/ResearchExecutionControllerTest.java`

**步骤 0：开始任务前更新状态**
- 状态改为 `in_progress`。

**步骤 1：建立文档 RED 清单**
- 检查 README 当前不存在一级独立章节“可靠 Agent 运行时与金融证据闭环”，记录缺失章节、API、图、评测结果和边界为 Red Evidence。

**步骤 2：运行 RED 检查**
- Run: `rg -n '^## 可靠 Agent 运行时与金融证据闭环$' README.md`
- Expected: FAIL/无匹配。

**步骤 3：更新文档**
- README 新增醒目的一级章节，独立展示节点恢复、EvidencePack、point-in-time、Claim–Evidence、事件流、决策复盘、默认/深度模式和 Agent Eval。
- 更新系统架构图、REST API、项目结构、测试命令和已知边界。
- 面试材料新增对应简历要点与追问，只有实测数字才能写入。
- changelog 汇总每个 Task 的提交、RED/GREEN 证据和最终验证。

**步骤 4：运行 GREEN 与全量验证**
- Run: `rg -n '^## 可靠 Agent 运行时与金融证据闭环$' README.md`
- Run: `zsh -ic 'jdk21 && mvn -q test'`
- Run: `cd frontend && npm test && npm run build`
- Run: `git diff --check`
- Run: `git diff -- src/main/resources/application.yml`
- Expected: 章节唯一命中；全部测试/构建通过；diff check 通过；application.yml 无差异。

**步骤 5：回写证据并完成**
- 填写最终 Red/Green Evidence，所有 Task 状态均为 `completed`。

**步骤 6：提交**
- Commit: `docs: 突出可靠 Agent 与金融证据闭环`
