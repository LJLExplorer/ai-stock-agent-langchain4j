# ISSUE-2026-09-06-runtime-log-reliability 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 修复最新运行日志中的异步执行/SSE 确定性缺陷，减少明确股票代码请求的无效 Planner 调用，并让 Judge 安全降级原因可诊断。

**架构：** 异步入口先持久化最小 `PLANNED` 状态，`WorkflowRunner` 仅以乐观锁替换合法占位状态；SSE 状态异常使用无 body 响应；明确股票代码使用现有受限解析器快速规划；Judge 保持单次调用并细分解析失败原因。

**技术栈：** Java 21、Spring Boot 3.3、Spring MVC/SSE、MongoDB 状态存储接口、LangChain4j、JUnit 5、Mockito、AssertJ

**相关文档：**
- 需求文档：`.ai/ISSUE-2026-09-06-runtime-log-reliability/requirements.md`
- 设计文档：`.ai/ISSUE-2026-09-06-runtime-log-reliability/design.md`
- 上游设计：`.ai/ISSUE-2026-09-05-agent-research-reliability/design.md`

**相关规范：**
- `yx-coder/AGENT.md` 与 `.ai-knowledge/base_knowledge/` 在当前仓库不存在。
- 不修改或提交 `src/main/resources/application.yml`。

## Task 1: 异步入口先持久化所有者占位状态

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchExecutionServiceTest test'`；4 tests 中 1 failure、1 error：启动返回后 `findOwned(...).orElseThrow()` 得到 empty，队列拒绝后也没有新增 FAILED 占位状态；与预期一致（yes）。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchExecutionServiceTest test'`；4 tests 全部通过（exit 0），验证返回前占位状态可查、队列拒绝状态转为 FAILED。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/service/ResearchExecutionService.java:62-92`
- Test: `src/test/java/com/ljl/ai/service/ResearchExecutionServiceTest.java`

**相关组件：**
- `ExecutionStateStore`
- `InMemoryRunEventPublisher`

**步骤 0：开始任务前更新状态**

- 将本 Task 的 `状态` 从 `pending` 改为 `in_progress`。
- 在状态更新完成前，禁止修改生产代码。

**步骤 1：编写失败测试**

- 在 `shouldPreallocateExecutionAndSessionBeforeRunningDeepResearch` 中用线程安全内存引用模拟 `save/load`。
- 让 `chatService.chat` 在 latch 上等待，调用 `start` 后立即断言：

```java
Optional<ExecutionState> accepted = service.findOwned(response.executionId(), "user-1");
assertThat(accepted).isPresent();
assertThat(accepted.orElseThrow().getWorkflowStatus()).isEqualTo(WorkflowStatus.PLANNED);
assertThat(accepted.orElseThrow().getSessionId()).isEqualTo("session-created");
```

- 在队列满测试中捕获第三个请求的 executionId 对应状态，断言拒绝后转换为 `FAILED`。

**步骤 2：运行测试确认失败**

Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchExecutionServiceTest test'`

Expected: FAIL；`findOwned` 在后台规划前返回 empty，或未发生占位状态首次保存。

填写 `Red Evidence`：
- Command
- Actual failure/output
- Match Expected: yes/no

**步骤 3：编写最小实现**

- 增加私有工厂创建占位状态：executionId、sessionId、userId、原始问题、空任务、`PLANNED`。
- 在提交后台任务前调用 `stateStore.save(state, -1)`。
- 保留 latch，确保后台线程不会早于 accepted 初始化阶段执行。
- 队列拒绝时复用 `recordFailure` 将占位状态转为 `FAILED` 后抛出稳定错误。
- `findOwned` 继续只读持久化状态并校验 userId。

**步骤 4：运行测试确认通过**

Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchExecutionServiceTest test'`

Expected: PASS。

**步骤 5：回写执行证据并标记完成**

- 填写本 Task 的 Red/Green Evidence。
- 将状态改为 `completed`。

## Task 2: 工作流安全替换合法占位状态

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=WorkflowRunnerTest test'`；7 tests 中 3 failures：未读取已有状态、合法占位版本未接管、非占位状态未拒绝；与预期一致（yes）。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=WorkflowRunnerTest test'`；7 tests 全部通过（exit 0），覆盖同步 insert、合法占位乐观锁替换、非法已有状态拒绝及原 Checkpoint 冲突行为。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/workflow/WorkflowRunner.java:39-47`
- Test: `src/test/java/com/ljl/ai/workflow/WorkflowRunnerTest.java`

**相关组件：**
- `ExecutionStateStore`
- Mongo 乐观锁版本语义

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`。

**步骤 1：编写失败测试**

- 新增合法占位状态测试：store.load 返回同 executionId、同 owner/session、`PLANNED`、plan 为 null、tasks 为空的 version 0 状态；断言首次保存使用 expectedVersion `0` 而不是 `-1`。
- 新增非法已有状态测试：已有状态带 plan 或状态非 `PLANNED` 时抛出 `EXECUTION_STATE_ALREADY_EXISTS`，且 workflow 不执行。
- 保留原同步首次插入测试，断言 store.load empty 时仍 `save(state, -1)`。

**步骤 2：运行测试确认失败**

Run: `zsh -ic 'jdk21 && mvn -q -Dtest=WorkflowRunnerTest test'`

Expected: FAIL；当前 `run` 不读取占位状态且始终 insert。

**步骤 3：编写最小实现**

- `run` 初始化 metadata 后读取同 executionId。
- 无记录时保持 `save(state, -1)`。
- 有记录时校验合法占位条件及 owner/session 一致性，将新状态 version 对齐到占位版本，再调用 `save(state, placeholder.version)`。
- 非法已有记录抛出稳定 `IllegalStateException("EXECUTION_STATE_ALREADY_EXISTS")`。

**步骤 4：运行测试确认通过**

Run: `zsh -ic 'jdk21 && mvn -q -Dtest=WorkflowRunnerTest test'`

Expected: PASS。

**步骤 5：回写执行证据并标记完成**

- 填写 Red/Green Evidence，将状态改为 `completed`。

## Task 6: 消除首次模型阻塞并展示真实事件进度

**状态：** completed

**Red Evidence：** 首次执行后端定向测试在 testCompile 失败，缺少 `DeepResearchService(assistant, publisher)` 和 `EXECUTION_ACCEPTED`；前端 9 tests 中 1 failure，拒绝未知 `EXECUTION_ACCEPTED`。补充角色开始事件测试后，后端再次因缺少 `ROLE_STARTED` 编译失败、前端再次因未知事件 1 failure；均与预期一致（yes）。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ChatServiceQueryRewriteTest,ResearchExecutionServiceTest,DeepResearchServiceTest,WorkflowRunnerTest test'` 全部通过；`npm test` 9 tests 全部通过。随后后端全量 `mvn -q test` 与前端 `npm run build` 均 exit 0。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/service/ChatService.java`
- Modify: `src/main/java/com/ljl/ai/research/DeepResearchService.java`
- Modify: `src/main/java/com/ljl/ai/agent/AgentConfig.java`
- Modify: `src/main/java/com/ljl/ai/observability/RunEvent.java`
- Modify: `src/main/java/com/ljl/ai/service/ResearchExecutionService.java`
- Modify: `src/main/java/com/ljl/ai/workflow/WorkflowRunner.java`
- Modify: `frontend/src/researchExecution.js`
- Modify: `frontend/src/App.jsx`
- Modify: `frontend/src/styles.css`
- Test: `src/test/java/com/ljl/ai/service/ChatServiceQueryRewriteTest.java`
- Test: `src/test/java/com/ljl/ai/research/DeepResearchServiceTest.java`
- Test: `src/test/java/com/ljl/ai/service/ResearchExecutionServiceTest.java`
- Test: `src/test/java/com/ljl/ai/workflow/WorkflowRunnerTest.java`
- Test: `frontend/src/researchExecution.test.js`

**步骤 1：编写失败测试**

- 明确六位股票代码时，查询解析直接使用原问题并且不调用 `QueryRewriteAssistant`。
- 异步请求接收事件使用 `EXECUTION_ACCEPTED`，不得提前宣称已进入多角色审议。
- Deep Research 每个角色及 Judge 开始、完成时分别发布 `ROLE_STARTED`、`ROLE_COMPLETED` 事件。
- 前端基于计划任务数、工具终态、证据包、7 个审议单元和答案就绪事件计算完成步数与百分比；终态成功才显示 100%。

**步骤 2：运行测试确认失败**

Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ChatServiceQueryRewriteTest,ResearchExecutionServiceTest,DeepResearchServiceTest test'` 以及 `npm test`

Expected: FAIL；当前显式代码仍同步调用改写模型，接收事件误用 `DEEP_RESEARCH_STARTED`，没有真实角色完成事件或百分比。

**步骤 3：编写最小实现**

- 在 `resolveRetrievalQuery` 最前面识别原问题中的六位股票代码并走确定性 `fallbackQuery`。
- 新增 `EXECUTION_ACCEPTED` 事件类型；实际进入深度研究节点时才发布 `DEEP_RESEARCH_STARTED`。
- `DeepResearchService` 注入可选 `RunEventPublisher`，每个角色以及 Judge 开始、完成/降级时发布无正文的受控元数据事件。
- `PLAN_CREATED` 携带 `taskCount`，前端以去重后的真实完成单元计算进度条。

**步骤 4：运行测试确认通过**

Run: 同步骤 2，并执行后端全量测试和前端生产构建。

**步骤 5：回写执行证据并标记完成**

- 填写 Red/Green Evidence，将状态改为 `completed`。

## Task 7: 统一异步占位与正式执行问题

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ChatServiceQueryRewriteTest,ResearchExecutionServiceTest test'` 在 testCompile 失败，报告缺少统一的 `ChatService.executionQuestion(message, orderId)`；与预期一致（yes）。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ChatServiceQueryRewriteTest,ResearchExecutionServiceTest,WorkflowRunnerTest test'` 全部通过（exit 0），覆盖独立 orderId 追加、已有同代码不重复追加及严格占位接管；`zsh -ic 'jdk21 && mvn -q test'` 后端全量测试通过（exit 0）。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/service/ChatService.java`
- Modify: `src/main/java/com/ljl/ai/service/ResearchExecutionService.java`
- Test: `src/test/java/com/ljl/ai/service/ChatServiceQueryRewriteTest.java`
- Test: `src/test/java/com/ljl/ai/service/ResearchExecutionServiceTest.java`

**步骤 1：编写失败测试**

- `orderId` 未出现在消息中时，占位状态与正式状态都使用追加股票上下文后的同一执行问题。
- 消息已经包含同一股票代码时不重复追加。

**步骤 2：运行测试确认失败**

Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ChatServiceQueryRewriteTest,ResearchExecutionServiceTest test'`

Expected: FAIL；当前占位状态保存原消息，正式状态保存追加 `orderId` 后的消息，导致严格占位校验拒绝。

**步骤 3：编写最小实现**

- 提取包内可见的确定性 `executionQuestion(message, orderId)`。
- `ChatService` 和 `ResearchExecutionService` 共同使用该函数。
- 日志补充受限稳定 `errorCode`，便于下次直接识别占位冲突。

**步骤 4：运行测试确认通过**

Run: 同步骤 2，并执行后端全量测试。

**步骤 5：回写执行证据并标记完成**

- 填写 Red/Green Evidence，将状态改为 `completed`。

## Task 8: 按证据范围并行审议并提供可读证据链接

**状态：** completed

**Red Evidence：** 后端 `zsh -ic 'jdk21 && mvn -q -Dtest=DeepResearchServiceTest,ChatServicePlannerTest,ChatMemoryServiceTest test'` 在 testCompile 失败，明确缺少三参数并发构造器、`plannedRoleCount`、`extractEvidenceSources` 和带来源的 `saveAssistantMessage`；前端 `npm test` 因缺少 `evidenceSourcesFromPack` 导出失败。与预期一致（yes）。

**Green Evidence：** 后端定向命令（额外包含 `StockAnalysisWorkflowTest`）全部通过（exit 0），并发屏障证明 TECHNICAL/BULL/BEAR/RISK 同时执行且 FUNDAMENTAL/NEWS 零调用；前端 `npm test` 11 tests 全通过。随后后端全量 `mvn -q test` 与前端 `npm run build` 均通过（exit 0）。

**补充 Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=DeepResearchServiceTest test'` 的 10 tests 中 1 failure：角色上下文只有原 EvidencePack 行，缺少分析日期、实际范围与未出现板块语义；与预期一致（yes）。

**补充 Green Evidence：** 同一 `DeepResearchServiceTest` 命令 10 tests 全部通过（exit 0），验证角色上下文包含权威分析日期、数据截止日、实际证据范围以及未请求板块/流水线问题不得写入风险的约束；补充后再次执行后端全量 `mvn -q test`、前端 11 tests 和生产构建，均通过（exit 0）。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/research/DeepResearchService.java`
- Modify: `src/main/java/com/ljl/ai/agent/DeepResearchAssistant.java`
- Modify: `src/main/java/com/ljl/ai/workflow/StockAnalysisWorkflow.java`
- Modify: `src/main/java/com/ljl/ai/research/EvidencePackBuilder.java`
- Modify: `src/main/java/com/ljl/ai/service/ChatService.java`
- Modify: `src/main/java/com/ljl/ai/memory/ChatMemoryService.java`
- Modify: `frontend/src/researchExecution.js`
- Modify: `frontend/src/App.jsx`
- Modify: `frontend/src/styles.css`
- Test: `src/test/java/com/ljl/ai/research/DeepResearchServiceTest.java`
- Test: `src/test/java/com/ljl/ai/service/ChatServicePlannerTest.java`
- Test: `src/test/java/com/ljl/ai/memory/ChatMemoryServiceTest.java`
- Test: `frontend/src/researchExecution.test.js`

**步骤 1：编写失败测试**

- 仅有技术/行情证据时不调用基本面和新闻角色，只运行技术、多空与风险角色，并证明专家调用可并发到达屏障。
- 深度研究事件携带本轮实际角色总数；前端按实际角色数计算完成步数，并维护真实的进行中角色集合。
- 将 EvidencePack 中的 evidenceId、来源名、原文 URL 和摘要映射到来源列表；原始 `[evidence:...]` 显示为可读、可点击的证据标签。
- 助手消息持久化其来源列表，重新加载会话后仍可查看证据。

**步骤 2：运行测试确认失败**

Run: `zsh -ic 'jdk21 && mvn -q -Dtest=DeepResearchServiceTest,ChatServicePlannerTest,ChatMemoryServiceTest test'` 以及 `npm test`

Expected: FAIL；当前固定串行调用六个角色、前端写死七个审议单元、异步结果不返回证据来源且证据标记为裸 ID。

**步骤 3：编写最小实现**

- 根据 EvidencePack 实际证据类型选择领域专家，始终保留 BULL、BEAR、RISK，专家阶段并行，Judge 在全部专家完成后单次汇总。
- 发布实际 `roleCount`，记录角色名及耗时；前端显示活动角色和真实已耗时，不用计时器伪造百分比。
- EvidencePack 证据统一映射成 `KnowledgeSource`，回答内证据标签链接到原文或对应来源项，并随助手消息持久化。
- 模型正文继续保持 `<redacted>`，避免日志泄露提示词、知识内容和用户数据。

**步骤 4：运行测试确认通过**

Run: 同步骤 2，并执行后端全量测试和前端生产构建。

**步骤 5：回写执行证据并标记完成**

- 填写 Red/Green Evidence，将状态改为 `completed`。

## Task 3: SSE 404 使用无响应体异常映射

**状态：** completed

**Red Evidence：** 首次 MockMvc 断言虽 exit 0，但控制台复现了通用处理器 ERROR 与 `HttpMediaTypeNotAcceptableException` WARN，说明仅断言最终 404 不足；加强为直接要求专用 handler 后，`zsh -ic 'jdk21 && mvn -q -Dtest=ResearchExecutionControllerTest test'` 在 testCompile 失败，报告缺少 `handleResponseStatusException`；与预期一致（yes）。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchExecutionControllerTest test'`；5 tests 全部通过（exit 0），缺失 SSE execution 返回空 body 404，控制台不再出现全局 ERROR 或媒体类型 WARN。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/controller/GlobalExceptionHandler.java:1-95`
- Test: `src/test/java/com/ljl/ai/controller/ResearchExecutionControllerTest.java`

**相关组件：**
- Spring MVC `ResponseStatusException`
- `text/event-stream`

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`。

**步骤 1：编写失败测试**

- 用 `standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())` 构建 MockMvc。
- mock `findOwned` 返回 empty。
- 以 `Accept: text/event-stream` 请求 `/events`，断言 404、空 body，并确保 resolved exception 不是 `HttpMediaTypeNotAcceptableException`。

**步骤 2：运行测试确认失败**

Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchExecutionControllerTest test'`

Expected: FAIL；当前通用异常处理器尝试序列化 JSON，导致媒体类型不匹配或返回 500。

**步骤 3：编写最小实现**

新增优先处理器：

```java
@ExceptionHandler(ResponseStatusException.class)
public ResponseEntity<Void> handleResponseStatusException(ResponseStatusException ex) {
    return ResponseEntity.status(ex.getStatusCode()).build();
}
```

404 属于预期请求结果，不记录 ERROR 堆栈。

**步骤 4：运行测试确认通过**

Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ResearchExecutionControllerTest test'`

Expected: PASS。

**步骤 5：回写执行证据并标记完成**

- 填写 Red/Green Evidence，将状态改为 `completed`。

## Task 4: 明确股票代码使用受限本地规划

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ChatServicePlannerTest test'`；8 tests 中 2 failures：明确代码请求仍进入 Planner，冗长 Planner 文本将单一 `NEWS_ANALYSIS` 扩张为全部 4 个任务；与预期一致（yes）。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=ChatServicePlannerTest test'`；8 tests 全部通过（exit 0），明确六位代码请求由受限本地解析器生成计划且 Planner 无交互，公司名请求仍调用 Planner。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/service/ChatService.java:393-429`
- Test: `src/test/java/com/ljl/ai/service/ChatServicePlannerTest.java`

**相关组件：**
- `PlannerTextParser`
- `PlanValidator`

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`。

**步骤 1：编写失败测试**

- 新增请求 `请对600519做技术分析`，断言得到 `600519.SH + TECHNICAL_ANALYSIS` 且 `verifyNoInteractions(planner)`。
- 新增不含明确代码的公司名请求，断言仍调用 Planner 并使用合法 JSON 结果。

**步骤 2：运行测试确认失败**

Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ChatServicePlannerTest test'`

Expected: FAIL；当前明确代码请求仍调用 Planner。

**步骤 3：编写最小实现**

- 在 `planForExecution` 开头调用 `PlannerTextParser.parse("", userMessage)`。
- 只在用户原文确实含六位代码且本地计划通过 `PlanValidator` 时返回。
- 其他请求继续原模型 JSON、文本兜底与白名单验证链路。

**步骤 4：运行测试确认通过**

Run: `zsh -ic 'jdk21 && mvn -q -Dtest=ChatServicePlannerTest test'`

Expected: PASS。

**步骤 5：回写执行证据并标记完成**

- 填写 Red/Green Evidence，将状态改为 `completed`。

## Task 5: Judge 降级输出稳定原因码

**状态：** completed

**Red Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=DeepResearchServiceTest test'`；7 tests 中 2 failures：空响应和无 JSON 文本仍统一返回 `JUDGE_FAILED`；与预期一致（yes）。

**Green Evidence：** `zsh -ic 'jdk21 && mvn -q -Dtest=DeepResearchServiceTest test'`；7 tests 全部通过（exit 0），空响应、无 JSON、非法 JSON/rating/confidence/date 均产生稳定原因码并安全降级，Judge 每次只调用一次。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/research/DeepResearchService.java:63-188`
- Test: `src/test/java/com/ljl/ai/research/DeepResearchServiceTest.java`

**相关组件：**
- Fastjson2
- `ResearchConclusion`

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`。

**步骤 1：编写失败测试**

- 把现有 `not-json` 预期改为 `JUDGE_MISSING_JSON_OBJECT`。
- 参数化或分别覆盖空响应、非法 JSON、非法 rating、非法 confidence、非法日期。
- 断言每种情况均返回 `INSUFFICIENT_DATA`、`degraded=true`，且 Judge 只调用一次。

**步骤 2：运行测试确认失败**

Run: `zsh -ic 'jdk21 && mvn -q -Dtest=DeepResearchServiceTest test'`

Expected: FAIL；当前大部分异常统一映射为 `JUDGE_FAILED`。

**步骤 3：编写最小实现**

- 在 `parseJudge/extractJson` 边界捕获并映射格式、枚举、数值、日期异常。
- 显式校验 confidence 非 null 且处于 `[0,1]`，summary 非空。
- 未知 evidenceId 保留脱敏稳定前缀，不记录模型正文。
- 日志增加 `responseLength`。

**步骤 4：运行测试确认通过**

Run: `zsh -ic 'jdk21 && mvn -q -Dtest=DeepResearchServiceTest test'`

Expected: PASS。

**步骤 5：回写执行证据并标记完成**

- 填写 Red/Green Evidence，将状态改为 `completed`。

## 完整验证

所有 Task 完成后执行：

```bash
zsh -ic 'jdk21 && mvn -q test'
cd frontend && npm test
cd frontend && npm run build
git diff --check
```

验收时重新扫描 `Logwork/application.log` 最新启动边界后的 ERROR/WARN。若本地外部服务未运行，记录无法执行真实链路复验的原因，不伪造成功证据。
