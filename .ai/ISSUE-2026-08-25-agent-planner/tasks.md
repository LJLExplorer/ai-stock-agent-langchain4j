# ISSUE-2026-08-25-agent-planner 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 为股票分析请求增加经过 `PlanValidator` 校验的 Agent Planner，并按合法任务裁剪执行工具。

**架构：** 无工具 Planner 先输出 `AgentPlan`，`PlanValidator` 严格拦截非法意图、标的和任务，并将任务映射为允许的工具。`ChatService` 使用校验后的工具集合构建请求级助手；Planner 失败则回退现有完整工具助手。

**技术栈：** Java 21、Spring Boot 3.3、LangChain4j 1.0.0-beta3、Fastjson2、JUnit 5、Mockito。

**相关文档：**
- 需求文档：`.ai/ISSUE-2026-08-25-agent-planner/requirements.md`
- 设计文档：`.ai/ISSUE-2026-08-25-agent-planner/design.md`

**相关规范：**
- 当前仓库未发现 `yx-coder/` 规范目录；遵循现有 Java/Spring/Lombok 风格。

**涉及组件：**
- 无数据库、缓存或分页组件变更。

### Task 1: 定义股票分析计划模型与任务映射

**状态：** completed

**Red Evidence：**

- Command: `mvn test -Dtest=AgentPlanTest`
- Actual: test compilation failed because `AgentPlan` and `StockAnalysisTask` did not exist.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn test -Dtest=AgentPlanTest`
- Actual: `Tests run: 1, Failures: 0, Errors: 0`，BUILD SUCCESS。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/agent/planner/StockAnalysisTask.java`
- Create: `src/main/java/com/ljl/ai/agent/planner/AgentPlan.java`
- Create: `src/test/java/com/ljl/ai/agent/planner/AgentPlanTest.java`

**步骤 0：开始任务前更新状态**

- 将本 Task 状态改为 `in_progress`，再修改生产代码。

**步骤 1：编写失败测试**

- 验证计划可以保存 `STOCK_ANALYSIS`、股票代码和任务列表。
- 验证任务枚举只包含 `MARKET_DATA`、`TECHNICAL_ANALYSIS`、`FINANCIAL_ANALYSIS`、`NEWS_ANALYSIS`，并提供每个任务对应的工具名。

**步骤 2：运行测试确认失败**

Run: `mvn test -Dtest=AgentPlanTest`

Expected: FAIL because the planner model types do not exist.

填写 Red Evidence。

**步骤 3：编写最小实现**

- 使用不可变/受控字段模型承载计划。
- 在任务枚举中集中维护工具名映射，禁止在 `ChatService` 分散硬编码。

**步骤 4：运行测试确认通过**

Run: `mvn test -Dtest=AgentPlanTest`

Expected: PASS。

填写 Green Evidence。

**步骤 5：回写证据并标记完成**

- 将 Red/Green Evidence 写回本任务并标记 `completed`。

**步骤 6：提交**

- 提交模型和测试文件，subject 使用 `feat: 增加股票分析计划模型`。

### Task 2: 实现 PlanValidator 的非法任务拦截

**状态：** completed

**Red Evidence：**

- Command: `mvn test -Dtest=PlanValidatorTest`
- Actual: test compilation failed because `PlanValidator` did not exist.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn test -Dtest=PlanValidatorTest`
- Actual: `Tests run: 3, Failures: 0, Errors: 0`，BUILD SUCCESS。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/agent/planner/PlanValidator.java`
- Create: `src/test/java/com/ljl/ai/agent/planner/PlanValidatorTest.java`

**步骤 0：开始任务前更新状态**

- 先把本 Task 改为 `in_progress`。

**步骤 1：编写失败测试**

- 合法股票分析计划通过，并返回去重后的任务和工具集合。
- 拒绝未知 intent、null/空任务、null/未知任务、重复任务、空股票代码和非股票代码格式。
- 确保预测、比较、组合、选股等越界能力不会被映射。

**步骤 2：运行测试确认失败**

Run: `mvn test -Dtest=PlanValidatorTest`

Expected: FAIL because `PlanValidator` does not exist or does not enforce the contract.

填写 Red Evidence。

**步骤 3：编写最小实现**

- 返回明确的校验结果对象或异常安全结果，不把非法任务静默放行。
- 统一股票代码标准化策略，兼容 `600519`、`600519.SH` 等输入。
- 仅输出允许的工具名集合供后续裁剪。

**步骤 4：运行测试确认通过**

Run: `mvn test -Dtest=PlanValidatorTest`

Expected: PASS。

填写 Green Evidence。

**步骤 5：回写证据并标记完成**

- 回写证据并标记 `completed`。

**步骤 6：提交**

- 提交 Validator 和测试，subject 使用 `feat: 增加股票分析计划校验`。

### Task 3: 增加无工具 Agent Planner Assistant

**状态：** completed

**Red Evidence：**

- Command: `mvn test -Dtest=AgentPlannerAssistantTest`
- Actual: test compilation failed because `AgentPlannerAssistant` did not exist.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn test -Dtest=AgentPlannerAssistantTest`
- Actual: `Tests run: 1, Failures: 0, Errors: 0`，BUILD SUCCESS。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/agent/agent/AgentPlannerAssistant.java`
- Modify: `src/main/java/com/ljl/ai/agent/agent/AgentConfig.java`
- Create: `src/test/java/com/ljl/ai/agent/agent/AgentPlannerAssistantTest.java`

**步骤 0：开始任务前更新状态**

- 先把本 Task 改为 `in_progress`。

**步骤 1：编写失败测试**

- 验证 Planner Assistant 暴露带结构化 JSON 约束的规划方法。
- 验证 Spring 配置会创建 Planner Bean，且 Planner 不注册任何业务工具。

**步骤 2：运行测试确认失败**

Run: `mvn test -Dtest=AgentPlannerAssistantTest`

Expected: FAIL because the interface/Bean does not exist.

填写 Red Evidence。

**步骤 3：编写最小实现**

- 添加只负责 `plan(String userMessage)` 的 `AiServices` 接口。
- 使用系统提示限定第一版 intent、股票代码和任务枚举，并要求只返回 JSON。
- 在 `AgentConfig` 中用同一 ChatLanguageModel 创建 Planner，不注册业务工具。

**步骤 4：运行测试确认通过**

Run: `mvn test -Dtest=AgentPlannerAssistantTest`

Expected: PASS。

填写 Green Evidence。

**步骤 5：回写证据并标记完成**

- 回写证据并标记 `completed`。

**步骤 6：提交**

- 提交 Planner 接口、配置和测试，subject 使用 `feat: 增加股票分析规划助手`。

### Task 4: 为股票助手增加按任务裁剪工具集的配置能力

**状态：** completed

**Red Evidence：**

- Command: `mvn test -Dtest=AgentConfigToolSelectionTest`
- Actual: test compilation failed because `AgentConfig.selectTools` did not exist.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn test -Dtest=AgentConfigToolSelectionTest`
- Actual: `Tests run: 2, Failures: 0, Errors: 0`，BUILD SUCCESS。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/agent/agent/AgentConfig.java`
- Create: `src/test/java/com/ljl/ai/agent/agent/AgentConfigToolSelectionTest.java`

**步骤 0：开始任务前更新状态**

- 先把本 Task 改为 `in_progress`。

**步骤 1：编写失败测试**

- 验证给定合法工具名集合时只选中对应工具。
- 验证未知工具名不会进入助手配置。
- 验证空集合仍可构建无工具助手，且既有完整助手配置不变。

**步骤 2：运行测试确认失败**

Run: `mvn test -Dtest=AgentConfigToolSelectionTest`

Expected: FAIL because configuration does not expose task-based selection.

填写 Red Evidence。

**步骤 3：编写最小实现**

- 为每个分析工具建立明确的工具名到 Bean 的白名单映射。
- 增加按合法工具名集合构建 `StockAnalysisAssistant` 的方法；不改变现有完整/无工具 Bean。

**步骤 4：运行测试确认通过**

Run: `mvn test -Dtest=AgentConfigToolSelectionTest`

Expected: PASS。

填写 Green Evidence。

**步骤 5：回写证据并标记完成**

- 回写证据并标记 `completed`。

**步骤 6：提交**

- 提交配置和测试，subject 使用 `feat: 支持按股票分析任务裁剪工具`。

### Task 5: 接入 ChatService 并实现 Planner 降级

**状态：** completed

**Red Evidence：**

- Command: `mvn test -Dtest=ChatServicePlannerTest`
- Actual: test compilation failed because `ChatService.planForExecution` did not exist.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn test -Dtest=ChatServicePlannerTest`
- Actual: `Tests run: 2, Failures: 0, Errors: 0`，BUILD SUCCESS；非法 JSON 和非法任务均安全降级。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/agent/service/ChatService.java`
- Create: `src/test/java/com/ljl/ai/agent/service/ChatServicePlannerTest.java`

**步骤 0：开始任务前更新状态**

- 先把本 Task 改为 `in_progress`。

**步骤 1：编写失败测试**

- 验证启用工具时会调用 Planner，并将合法计划转换为裁剪后的执行助手输入。
- 验证 Planner 抛异常、返回非法计划时，ChatService 回退现有完整工具助手。
- 验证 `enableTools=false` 时不调用 Planner。

**步骤 2：运行测试确认失败**

Run: `mvn test -Dtest=ChatServicePlannerTest`

Expected: FAIL because ChatService has no Planner integration.

填写 Red Evidence。

**步骤 3：编写最小实现**

- 注入 Planner 和 Validator。
- 在现有工具助手调用前规划；校验成功则构建按任务裁剪的助手，并把标准化计划摘要加入 prompt。
- 任意规划异常或非法计划记录 warn 并继续原有完整助手路径。
- 保持 RAG、短期/长期记忆、工具调用收集、消息持久化和异常清理代码行为不变。

**步骤 4：运行测试确认通过**

Run: `mvn test -Dtest=ChatServicePlannerTest`

Expected: PASS。

填写 Green Evidence。

**步骤 5：回写证据并标记完成**

- 回写证据并标记 `completed`。

**步骤 6：提交**

- 提交服务和测试，subject 使用 `feat: 接入股票分析 Agent Planner`。

### Task 6: 全量回归与文档更新

**状态：** completed

**Red Evidence：**

- Command: `mvn test`
- Actual: 在文档更新前执行全量回归，项目已完成编译并进入 36 个测试用例执行；结果无失败。
- Match Expected: yes

**Green Evidence：**

- Command: `mvn test`
- Actual: `Tests run: 36, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS；`git diff --check` 无输出。

**涉及文件：**
- Modify: `README.md`（仅补充已实现的 Planner 架构说明，保留用户现有未提交修改）
- Modify: `.ai/ISSUE-2026-08-25-agent-planner/tasks.md`

**步骤 0：开始任务前更新状态**

- 先把本 Task 改为 `in_progress`。

**步骤 1：编写失败测试**

- 无新增测试；使用前置任务测试作为回归基线。

**步骤 2：运行测试确认失败**

Run: `mvn test`

Expected: 若存在回归则先修复，不能以失败结果结束。

填写 Red Evidence 为当前回归结果。

**步骤 3：编写最小实现**

- 更新 README 的架构和面试说明，准确描述 Planner、Validator、任务映射、工具裁剪和降级策略。

**步骤 4：运行测试确认通过**

Run: `mvn test`

Expected: PASS。

填写 Green Evidence。

**步骤 5：回写证据并标记完成**

- 回写所有任务证据和最终验证结果，标记本 Task `completed`。

**步骤 6：提交**

- 按用户要求决定是否提交；不得覆盖 README 中原有用户修改。
