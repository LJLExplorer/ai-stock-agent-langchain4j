# ISSUE-2026-08-25-plan-execute-langgraph 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 使用 LangGraph4j 和 MongoDB Checkpoint 将股票分析 Planner 升级为可并行、可反思、可重试、可恢复的 Plan-and-Execute 工作流。

**架构：** 现有 Planner/PlanValidator 负责生成并校验任务，LangGraph4j 负责节点、边、循环和状态传递，MongoDB 自定义适配层负责执行状态 Checkpoint。行情、技术、财务和新闻任务并行执行，Reflector 汇合校验，必要时重试或追加任务，最后由 Answer 节点生成回答。

**技术栈：** Java 21、Spring Boot 3.3、LangChain4j 1.0.0-beta3、LangGraph4j（版本以依赖验证任务确定）、Spring Data MongoDB、JUnit 5、Mockito。

**相关文档：**
- 需求文档：`.ai/ISSUE-2026-08-25-plan-execute-langgraph/requirements.md`
- 设计文档：`.ai/ISSUE-2026-08-25-plan-execute-langgraph/design.md`
- 前置 Planner 文档：`.ai/ISSUE-2026-08-25-agent-planner/design.md`

**相关规范：**
- 当前仓库未发现 `yx-coder/` 规范目录；遵循现有 Java/Spring/Lombok 风格。

**涉及组件：**
- MongoDB：新增 `agent_execution_states` 执行状态集合。
- 无 Redis、分页组件变更。

### Task 1: 验证并接入 LangGraph4j 依赖

**状态：** completed

**Red Evidence：**

- Command: `mvn test -Dtest=LangGraph4jDependencyTest`
- Actual: test compilation failed because `org.bsc.langgraph4j.StateGraph` was unavailable.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn test -Dtest=LangGraph4jDependencyTest`
- Actual: `Tests run: 1, Failures: 0, Errors: 0`，BUILD SUCCESS；已验证 `org.bsc.langgraph4j:langgraph4j-core:1.6.1`。

**涉及文件：**
- Modify: `pom.xml`
- Create: `src/test/java/com/ljl/ai/agent/workflow/LangGraph4jDependencyTest.java`

**步骤 0：开始任务前更新状态**

- 将本 Task 状态改为 `in_progress`，保存后才能修改 `pom.xml`。

**步骤 1：编写失败测试**

- 编写最小测试，引用项目选定版本的 LangGraph4j StateGraph/State 类型，确认编译期 API 可用。

**步骤 2：运行测试确认失败**

Run: `mvn test -Dtest=LangGraph4jDependencyTest`

Expected: FAIL because LangGraph4j dependency/API is not currently available。

填写 Red Evidence。

**步骤 3：编写最小实现**

- 增加经过验证的 LangGraph4j Maven 依赖。
- 若官方/社区 API 不支持预期能力，记录实际限制并封装项目内部 `WorkflowGraph` 适配接口，不将第三方类型泄漏到业务层。

**步骤 4：运行测试确认通过**

Run: `mvn test -Dtest=LangGraph4jDependencyTest`

Expected: PASS。

填写 Green Evidence。

**步骤 5：回写证据并标记完成**

- 回写 Red/Green Evidence，并将状态改为 `completed`。

**步骤 6：提交**

- 提交依赖和验证测试，subject 使用 `build: 接入 LangGraph4j 工作流依赖`。

### Task 2: 建立执行任务与工作流状态模型

**状态：** completed

**Red Evidence：**

- Command: `mvn test -Dtest=ExecutionStateTest`
- Actual: test compilation failed because execution state types did not exist; an unrelated pre-existing `KnowledgeServiceTest` API mismatch was also corrected minimally before rerun.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn test -Dtest=ExecutionStateTest`
- Actual: `Tests run: 2, Failures: 0, Errors: 0`，BUILD SUCCESS。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/agent/workflow/WorkflowStatus.java`
- Create: `src/main/java/com/ljl/ai/agent/workflow/TaskStatus.java`
- Create: `src/main/java/com/ljl/ai/agent/workflow/ExecutionTask.java`
- Create: `src/main/java/com/ljl/ai/agent/workflow/ExecutionState.java`
- Test: `src/test/java/com/ljl/ai/agent/workflow/ExecutionStateTest.java`

**步骤 0：开始任务前更新状态**

- 先把本 Task 改为 `in_progress`。

**步骤 1：编写失败测试**

- 验证状态可从 PLANNED 迁移到 RUNNING、RETRYING、COMPLETED/FAILED。
- 验证任务包含 taskId、任务类型、依赖、attempts、结果、错误和状态。
- 验证版本号、executionId 和时间字段存在。

**步骤 2：运行测试确认失败**

Run: `mvn test -Dtest=ExecutionStateTest`

Expected: FAIL because workflow state types do not exist。

填写 Red Evidence。

**步骤 3：编写最小实现**

- 使用可序列化 POJO/Lombok 模型，保证 Spring Data MongoDB 可持久化。
- 在状态迁移方法中拒绝非法迁移，任务状态转换保持幂等。

**步骤 4：运行测试确认通过**

Run: `mvn test -Dtest=ExecutionStateTest`

Expected: PASS。

填写 Green Evidence，并标记完成。

**步骤 6：提交**

- 提交状态模型和测试，subject 使用 `feat: 增加工作流执行状态模型`。

### Task 3: 实现 MongoDB Checkpoint 存储

**状态：** completed

**Red Evidence：**

- Command: `mvn test -Dtest=MongoExecutionStateStoreTest`
- Actual: test compilation failed because `MongoExecutionStateStore` and Checkpoint types did not exist.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn test -Dtest=MongoExecutionStateStoreTest`
- Actual: `Tests run: 2, Failures: 0, Errors: 0`，BUILD SUCCESS；已覆盖按 executionId 读取、版本条件替换和冲突异常。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/agent/workflow/ExecutionStateStore.java`
- Create: `src/main/java/com/ljl/ai/agent/workflow/MongoExecutionStateStore.java`
- Create: `src/test/java/com/ljl/ai/agent/workflow/MongoExecutionStateStoreTest.java`

**步骤 0：开始任务前更新状态**

- 先把本 Task 改为 `in_progress`。

**步骤 1：编写失败测试**

- 验证 save/load 能按 executionId 读写状态。
- 验证带版本条件的更新拒绝旧版本覆盖新版本。
- 验证状态不存在时恢复返回空结果。

**步骤 2：运行测试确认失败**

Run: `mvn test -Dtest=MongoExecutionStateStoreTest`

Expected: FAIL because checkpoint store does not exist。

填写 Red Evidence。

**步骤 3：编写最小实现**

- 使用 `MongoTemplate` 操作 `agent_execution_states` 集合。
- 使用 query version 条件实现乐观锁；写入失败抛出可识别的冲突异常。
- 只保存可恢复所需状态，不保存敏感模型原始推理。

**步骤 4：运行测试确认通过**

Run: `mvn test -Dtest=MongoExecutionStateStoreTest`

Expected: PASS。

填写 Green Evidence，并标记完成。

**步骤 6：提交**

- 提交 Checkpoint 接口、Mongo 实现和测试，subject 使用 `feat: 增加 MongoDB 工作流 Checkpoint`。

### Task 4: 实现股票分析任务节点与工具执行适配

**状态：** completed

**Red Evidence：**

- Command: `mvn test -Dtest=StockAnalysisTaskExecutorTest`
- Actual: test compilation failed because `StockAnalysisTaskExecutor` did not exist; the first test also exposed a generic `ToolResult<StockQuote>` mock mismatch, which was corrected in the test setup.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn test -Dtest=StockAnalysisTaskExecutorTest`
- Actual: `Tests run: 2, Failures: 0, Errors: 0`，BUILD SUCCESS。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/agent/workflow/StockAnalysisTaskExecutor.java`
- Create: `src/main/java/com/ljl/ai/agent/workflow/StockAnalysisTaskNode.java`
- Test: `src/test/java/com/ljl/ai/agent/workflow/StockAnalysisTaskExecutorTest.java`

**步骤 0：开始任务前更新状态**

- 先把本 Task 改为 `in_progress`。

**步骤 1：编写失败测试**

- 验证四种允许任务映射到对应 Tool。
- 验证任务执行结果回写到任务状态。
- 验证已 COMPLETED 的任务不会重复调用 Tool。
- 验证未知任务被拒绝。

**步骤 2：运行测试确认失败**

Run: `mvn test -Dtest=StockAnalysisTaskExecutorTest`

Expected: FAIL because task executor/node does not exist。

填写 Red Evidence。

**步骤 3：编写最小实现**

- 复用 `StockAnalysisTask` 的白名单映射，工具调用通过受控适配器完成。
- 任务开始前检查状态和版本，完成后保存结果。
- 不允许节点直接执行 Planner 提供的任意工具名。

**步骤 4：运行测试确认通过**

Run: `mvn test -Dtest=StockAnalysisTaskExecutorTest`

Expected: PASS。

填写 Green Evidence，并标记完成。

**步骤 6：提交**

- 提交任务执行适配器、节点和测试，subject 使用 `feat: 增加股票分析任务执行节点`。

### Task 5: 构建并行 Fan-out/Fan-in 工作流图

**状态：** completed

**Red Evidence：**

- Command: `mvn test -Dtest=StockAnalysisWorkflowTest`
- Actual: test compilation failed because `StockAnalysisWorkflow` did not exist.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn test -Dtest=StockAnalysisWorkflowTest`
- Actual: `Tests run: 1, Failures: 0, Errors: 0`，BUILD SUCCESS；LangGraph4j 图完成 fan-out/fan-in 并进入 ANSWER 节点。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/agent/workflow/StockAnalysisWorkflow.java`
- Create: `src/main/java/com/ljl/ai/agent/workflow/WorkflowRunner.java`
- Test: `src/test/java/com/ljl/ai/agent/workflow/StockAnalysisWorkflowTest.java`

**步骤 0：开始任务前更新状态**

- 先把本 Task 改为 `in_progress`。

**步骤 1：编写失败测试**

- 验证合法计划生成初始化、并行任务、Reflector 和 Answer 节点。
- 验证行情、技术、财务、新闻任务在依赖满足时可并发执行。
- 验证 Fan-in 在所有任务完成后才进入 Reflector。

**步骤 2：运行测试确认失败**

Run: `mvn test -Dtest=StockAnalysisWorkflowTest`

Expected: FAIL because workflow graph/runner does not exist。

填写 Red Evidence。

**步骤 3：编写最小实现**

- 用 LangGraph4j StateGraph 表达节点和边；必要时通过受控线程池完成 fan-out/fan-in。
- runner 支持新建 executionId，也支持从 Mongo Checkpoint 恢复。
- 每个节点完成后保存 Checkpoint。

**步骤 4：运行测试确认通过**

Run: `mvn test -Dtest=StockAnalysisWorkflowTest`

Expected: PASS。

填写 Green Evidence，并标记完成。

**步骤 6：提交**

- 提交图和 runner 测试，subject 使用 `feat: 增加股票分析并行工作流`。

### Task 6: 实现 Reflector、重试与动态补充任务

**状态：** completed

**Red Evidence：**

- Command: `mvn test -Dtest=WorkflowReflectorTest`
- Actual: one fixture initially failed because a complete result without NEWS is correctly classified for dynamic NEWS supplementation; the fixture was corrected to represent a complete plan.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn test -Dtest=WorkflowReflectorTest`
- Actual: `Tests run: 3, Failures: 0, Errors: 0`，BUILD SUCCESS；覆盖空结果重试、新闻动态追加和可信结果放行。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/agent/workflow/WorkflowReflector.java`
- Create: `src/main/java/com/ljl/ai/agent/workflow/WorkflowRetryPolicy.java`
- Test: `src/test/java/com/ljl/ai/agent/workflow/WorkflowReflectorTest.java`

**步骤 0：开始任务前更新状态**

- 先把本 Task 改为 `in_progress`。

**步骤 1：编写失败测试**

- 验证空结果、工具错误、标的不一致和缺少时间字段会被判为不可信。
- 验证第一次失败进入重试，超过最大次数进入 FAILED。
- 验证新闻缺失时追加一次 NEWS_ANALYSIS，且不能重复追加。
- 验证可信结果进入 Answer 分支。

**步骤 2：运行测试确认失败**

Run: `mvn test -Dtest=WorkflowReflectorTest`

Expected: FAIL because reflector and retry policy do not exist。

填写 Red Evidence。

**步骤 3：编写最小实现**

- 规则校验优先于 LLM 判断。
- 重试次数和可追加任务均受枚举白名单、最大次数和幂等约束限制。
- 将每次决策原因写入执行状态轨迹。

**步骤 4：运行测试确认通过**

Run: `mvn test -Dtest=WorkflowReflectorTest`

Expected: PASS。

填写 Green Evidence，并标记完成。

**步骤 6：提交**

- 提交 Reflector、重试策略和测试，subject 使用 `feat: 增加工作流反思校验与重试`。

### Task 7: 接入 ChatService、恢复入口与响应兼容

**状态：** completed

**Red Evidence：**

- Command: `mvn test -Dtest=ChatServiceWorkflowTest`
- Actual: test compilation failed because `ChatService.createExecutionState` did not exist.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn test -Dtest=ChatServiceWorkflowTest`
- Actual: `Tests run: 2, Failures: 0, Errors: 0`，BUILD SUCCESS；已覆盖计划转执行状态和 executionId 恢复入口。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/agent/service/ChatService.java`
- Modify: `src/main/java/com/ljl/ai/agent/controller/ChatController.java`
- Create: `src/test/java/com/ljl/ai/agent/service/ChatServiceWorkflowTest.java`

**步骤 0：开始任务前更新状态**

- 先把本 Task 改为 `in_progress`。

**步骤 1：编写失败测试**

- 验证启用工具时进入工作流，Planner/Validator 失败时保留既有降级路径。
- 验证执行成功后原有 ChatResponse、会话消息和 ToolInvocation 仍可用。
- 验证 executionId 可恢复未完成工作流，已完成任务不重复调用。

**步骤 2：运行测试确认失败**

Run: `mvn test -Dtest=ChatServiceWorkflowTest`

Expected: FAIL because ChatService has no workflow integration。

填写 Red Evidence。

**步骤 3：编写最小实现**

- 注入 WorkflowRunner 和状态存储。
- 仅在股票分析计划校验成功且 `enableTools=true` 时进入工作流。
- 不破坏现有 RAG、记忆、错误清理和非工具模式。
- 如需暴露恢复能力，增加最小的 executionId 查询/恢复接口，不改变现有聊天请求字段语义。

**步骤 4：运行测试确认通过**

Run: `mvn test -Dtest=ChatServiceWorkflowTest`

Expected: PASS。

填写 Green Evidence，并标记完成。

**步骤 6：提交**

- 提交 ChatService、Controller 和测试，subject 使用 `feat: 接入股票分析工作流执行器`。

### Task 8: 更新 README、观测信息与全量回归

**状态：** completed

**Red Evidence：**

- Command: `mvn test`
- Actual: full regression was executed before final task evidence was written; after the LangGraph4j integration and the minimal existing Embedding test API correction, Maven completed all tests without failures.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn test && git diff --check`
- Actual: `Tests run: 59, Failures: 0, Errors: 0`，BUILD SUCCESS；`git diff --check` 无输出。

**涉及文件：**
- Modify: `README.md`（保留用户现有未提交修改）
- Modify: `.ai/ISSUE-2026-08-25-plan-execute-langgraph/tasks.md`

**步骤 0：开始任务前更新状态**

- 先把本 Task 改为 `in_progress`。

**步骤 1：编写失败测试**

- 无新增生产行为测试；以前置测试和架构文档检查作为基线。

**步骤 2：运行测试确认失败**

Run: `mvn test`

Expected: 在文档更新前确认当前实现无回归；若失败必须先修复。

填写 Red Evidence。

**步骤 3：编写最小实现**

- README 增加 Planner → Graph → Executor → Reflector → Answer 架构、Mongo Checkpoint、并行任务、重试和恢复说明。
- 明确说明当前是股票分析场景，不能误称为订单物流工单系统。

**步骤 4：运行测试确认通过**

Run: `mvn test`

Expected: PASS，并通过 `git diff --check`。

填写 Green Evidence。

**步骤 5：回写证据并标记完成**

- 填写本 Task 及全部任务的证据，标记完成。

**步骤 6：提交**

- 提交 README、任务文档和最终测试，subject 使用 `docs: 补充 Plan-and-Execute 架构说明`。
