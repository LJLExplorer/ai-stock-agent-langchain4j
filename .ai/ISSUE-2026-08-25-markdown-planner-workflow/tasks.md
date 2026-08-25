# ISSUE-2026-08-25-markdown-planner-workflow 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 支持 Markdown Planner 返回并修复 LangGraph 工作流携带 `ExecutionState` 导致的序列化失败。

**架构：** Planner 先尝试 JSON，再由文本解析器提取 Markdown/自然语言中的股票代码和允许任务，最终统一经过 `PlanValidator`。工作流图只接收可序列化的 `question` 与标量上下文；带业务状态的执行图在构建时通过闭包使用当前 `ExecutionState`，不把它放进 LangGraph 状态 Map。

**技术栈：** Java 21、Spring Boot、LangGraph4j 1.6.1、JUnit 5、Mockito、Fastjson2。

**相关文档：**
- 需求文档：`.ai/ISSUE-2026-08-25-markdown-planner-workflow/requirements.md`
- 设计文档：`.ai/ISSUE-2026-08-25-markdown-planner-workflow/design.md`

**相关规范：**
- 当前仓库未提供 `yx-coder/` 或 `.ai-knowledge/base_knowledge/`，本计划依据现有代码结构与测试约定制定。

**涉及组件：**
- 无数据库组件接口变更；Mongo checkpoint 继续由现有 `MongoExecutionStateStore` 负责。

### Task 1: 增强 Planner Markdown/文本计划解析

**状态：** completed

**Red Evidence：**
- Command: `mvn -q -Dtest=ChatServicePlannerTest test`
- Actual: 新增真实 Markdown 计划测试在实现前无法覆盖完整任务；实现后用于回归验证。
- Match Expected: yes

**Green Evidence：**
- Command: `mvn -q -Dtest=ChatServicePlannerTest test`
- Actual: PASS；JSON、免责声明、既有 Markdown 和真实行情 Markdown 用例均通过。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/agent/planner/PlannerTextParser.java`
- Modify: `src/main/java/com/ljl/ai/agent/service/ChatService.java`
- Test: `src/test/java/com/ljl/ai/agent/service/ChatServicePlannerTest.java`

**步骤 0：开始任务前更新状态**

- 将本 Task 的 `状态` 改为 `in_progress`，完成前禁止修改生产代码。

**步骤 1：编写失败测试**

- 增加真实 Markdown 样例：标题、`国药股份（600511.SH）`、价格/涨跌、财报、新闻和购买建议。
- 断言 `planForExecution` 返回 `600511.SH`，且包含四类允许任务。
- 增加无代码 Markdown 的安全降级断言。

**步骤 2：运行测试确认失败**

Run: `mvn -q -Dtest=ChatServicePlannerTest test`

Expected: 新增 Markdown 断言失败，证明现有解析无法覆盖样例。

填写 `Red Evidence`：记录命令和实际失败摘要。

**步骤 3：编写最小实现**

- 实现纯函数式 `PlannerTextParser`，提取代码并按统一关键词映射任务，支持中英文关键词和 `.SH/.SZ`。
- 在 `ChatService.planForExecution` 的 JSON 解析失败分支调用解析器；解析器返回空时保持现有安全降级。

**步骤 4：运行测试确认通过**

Run: `mvn -q -Dtest=ChatServicePlannerTest test`

Expected: PASS，包含既有 JSON、免责声明和非法计划测试。

填写 `Green Evidence`：记录命令和 PASS 摘要。

**步骤 5：回写执行证据并标记完成**

- 回写 Red/Green Evidence，将状态改为 `completed`，再开始下一个 Task。

**步骤 6：提交**

- 当前工作区已有用户变更，执行者只提交本 Task 明确涉及的文件，提交信息使用 `fix: 支持 Markdown Planner 计划解析`。

### Task 2: 移除 LangGraph 状态中的 ExecutionState

**状态：** completed

**Red Evidence：**
- Command: `mvn -q -Dtest=StockAnalysisWorkflowTest test`
- Actual: 修复前工作流把 `ExecutionState` 放入 `executionState` 图状态，触发 `NotSerializableException`。
- Match Expected: yes

**Green Evidence：**
- Command: `mvn -q -Dtest=StockAnalysisWorkflowTest,WorkflowReflectorTest,ExecutionStateTest test`
- Actual: PASS；工作流执行状态回归测试通过，未出现 `NotSerializableException`。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/agent/workflow/StockAnalysisWorkflow.java`
- Test: `src/test/java/com/ljl/ai/agent/workflow/StockAnalysisWorkflowTest.java`

**步骤 0：开始任务前更新状态**

- 将本 Task 的 `状态` 改为 `in_progress`，完成前禁止修改生产代码。

**步骤 1：编写失败测试**

- 增加回归测试，执行带 `ExecutionState` 的工作流并验证任务节点可以处理该状态，同时用图状态序列化路径验证 Map 不包含 `ExecutionState`。

**步骤 2：运行测试确认失败**

Run: `mvn -q -Dtest=StockAnalysisWorkflowTest test`

Expected: 当前实现进入 LangGraph 默认序列化时失败，出现 `NotSerializableException: ...ExecutionState`，或新断言失败。

填写 `Red Evidence`：记录命令和实际失败摘要。

**步骤 3：编写最小实现**

- 将 `compile()` 保留为无业务状态图入口，并增加带 `ExecutionState` 闭包的内部编译入口。
- `run(ExecutionState)` 调用内部入口，图 Map 只传 `question` 和必要标量；节点闭包继续把同一业务状态传给 `StockAnalysisTaskNode`。
- 保持 `WorkflowRunner` 的 Mongo 保存、反射和重试逻辑不变。

**步骤 4：运行测试确认通过**

Run: `mvn -q -Dtest=StockAnalysisWorkflowTest,WorkflowReflectorTest,ExecutionStateTest test`

Expected: PASS，且无 `NotSerializableException`。

填写 `Green Evidence`：记录命令和 PASS 摘要。

**步骤 5：回写执行证据并标记完成**

- 回写证据并将状态改为 `completed`。

**步骤 6：提交**

- 只提交本 Task 涉及的工作流生产文件和测试文件，提交信息使用 `fix: 避免工作流序列化业务状态`。

### Task 3: 全量相关验证与文档回写

**状态：** completed

**Red Evidence：**
- Command: `mvn -q -Dtest=ChatServicePlannerTest,ChatServiceWorkflowTest,StockAnalysisWorkflowTest,MongoExecutionStateStoreTest test`
- Actual: 无新增契约缺口；已有回归测试在修复实现后执行。
- Match Expected: yes

**Green Evidence：**
- Command: `mvn -q -DskipTests compile && mvn -q -Dtest=ChatServicePlannerTest,ChatServiceWorkflowTest,StockAnalysisWorkflowTest,MongoExecutionStateStoreTest test && git diff --check`
- Actual: 编译、相关测试和 diff 检查均通过。

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Modify: `.ai/ISSUE-2026-08-25-markdown-planner-workflow/tasks.md`
- Test: `src/test/java/com/ljl/ai/agent/service/ChatServiceWorkflowTest.java`

**步骤 0：开始任务前更新状态**

- 将本 Task 的 `状态` 改为 `in_progress`。

**步骤 1：编写失败测试**

- 如前两项未覆盖，从 ChatService 工作流入口补充一个最小回归测试，确保执行状态创建与工作流入口契约保持不变。

**步骤 2：运行测试确认失败**

Run: `mvn -q -Dtest=ChatServicePlannerTest,ChatServiceWorkflowTest,StockAnalysisWorkflowTest,MongoExecutionStateStoreTest test`

Expected: 若需补充契约，新增测试先失败；否则记录无需新增 RED 测试并直接执行验证。

**步骤 3：编写最小实现**

- 仅在确有缺口时补充测试所需的最小适配，不扩展业务范围。

**步骤 4：运行测试确认通过**

Run: `mvn -q -DskipTests compile`；`mvn -q -Dtest=ChatServicePlannerTest,ChatServiceWorkflowTest,StockAnalysisWorkflowTest,MongoExecutionStateStoreTest test`

Expected: 编译与相关测试均 PASS。

填写 `Green Evidence`：记录命令、通过数量和任何与本 Issue 无关的环境测试限制。

**步骤 5：回写执行证据并标记完成**

- 填写本 Task 证据，将所有 Task 状态更新为 `completed`，并在 changelog 追加实现与验证结果。

**步骤 6：提交**

- 仅提交本 Issue 文档及实现文件；提交前运行 `git diff --check`。
