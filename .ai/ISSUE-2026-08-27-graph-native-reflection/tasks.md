# ISSUE-2026-08-27-graph-native-reflection 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 将股票分析改造为由 StateGraph 驱动的 Reflector/Critic/Generator 工作流。

**架构：** ChatService 在图外完成 Planner 和会话职责；StateGraph 在图内执行任务、反思、裁决、重试/补新闻并生成最终答案。ExecutionState 是唯一业务状态并按节点持久化。

**技术栈：** Java 21、Spring Boot、LangGraph4j、LangChain4j、JUnit 5、Mockito。

**相关文档：**
- 需求文档：`.ai/ISSUE-2026-08-27-graph-native-reflection/requirements.md`
- 设计文档：`.ai/ISSUE-2026-08-27-graph-native-reflection/design.md`

### Task 1: 固化 Critic 决策与新闻补齐测试

**状态：** completed

**Red Evidence：**

- Command: `mvn -q -Dtest=WorkflowReflectorTest,WorkflowCriticTest test`
- Actual: test compilation failed because `WorkflowCritic` did not exist.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn -q -Dtest=WorkflowReflectorTest,WorkflowCriticTest test`
- Actual: PASS.

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/agent/workflow/WorkflowReflector.java`
- Create: `src/main/java/com/ljl/ai/agent/workflow/WorkflowCritic.java`
- Modify: `src/test/java/com/ljl/ai/agent/workflow/WorkflowReflectorTest.java`
- Create: `src/test/java/com/ljl/ai/agent/workflow/WorkflowCriticTest.java`

1. 先更新本 Task 状态为 `in_progress`。
2. 编写失败测试：可信结果为 ANSWER；失败任务为 RETRY；缺新闻为 ADD_NEWS；超过上限为 FAILED。
3. 运行 `mvn -q -Dtest=WorkflowReflectorTest,WorkflowCriticTest test`，记录 Red Evidence。
4. 用最小枚举/record 表达 Critic 路由决定，保留 WorkflowReflector 的新闻补齐规则。
5. 重跑定向测试，记录 Green Evidence 并标记 `completed`。

### Task 2: 将 StateGraph 改为真实条件工作流

**状态：** completed

**Red Evidence：**

- Command: `mvn -q -Dtest=StockAnalysisWorkflowTest test`
- Actual: expected `ExecutionState.currentNode` to be `ANSWER`, but was `null`.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn -q -Dtest=WorkflowReflectorTest,WorkflowCriticTest,StockAnalysisWorkflowTest,ChatServiceWorkflowTest test`
- Actual: PASS.

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/agent/workflow/StockAnalysisWorkflow.java`
- Modify: `src/main/java/com/ljl/ai/agent/workflow/WorkflowRunner.java`
- Modify: `src/test/java/com/ljl/ai/agent/workflow/StockAnalysisWorkflowTest.java`

1. 更新状态为 `in_progress`。
2. 编写失败测试，验证图内依次执行 REFLECTOR、CRITIC，并按路由进入重试、补新闻、ANSWER 或 FAILED。
3. 运行 `mvn -q -Dtest=StockAnalysisWorkflowTest test`，记录 Red Evidence。
4. 实现节点动作、条件边、节点后 Checkpoint；删除 Runner 图外循环和 `run(String question)`。
5. 重跑定向测试，记录 Green Evidence 并标记完成。

### Task 3: 实现无工具 Answer Generator 并接入 ChatService

**状态：** completed

**Red Evidence：**

- Command: `mvn -q -Dtest=WorkflowAnswerGeneratorTest test`
- Actual: test compilation failed because `WorkflowAnswerGenerator` did not exist.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn -q -Dtest=WorkflowAnswerGeneratorTest,ChatServiceWorkflowTest,StockAnalysisWorkflowTest test`
- Actual: PASS.

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/agent/workflow/WorkflowAnswerGenerator.java`
- Modify: `src/main/java/com/ljl/ai/agent/service/ChatService.java`
- Modify: `src/test/java/com/ljl/ai/agent/service/ChatServiceWorkflowTest.java`
- Create: `src/test/java/com/ljl/ai/agent/workflow/WorkflowAnswerGeneratorTest.java`

1. 更新状态为 `in_progress`。
2. 编写失败测试：Generator 只接收已验收结果、写入 finalAnswer；ChatService 不重复调用助手生成工作流答案。
3. 运行 `mvn -q -Dtest=WorkflowAnswerGeneratorTest,ChatServiceWorkflowTest test`，记录 Red Evidence。
4. 复用不注册工具的 StockAnalysisAssistant 实现答案生成，保持 RAG 上下文可用。
5. 重跑定向测试，记录 Green Evidence 并标记完成。

### Task 4: 删除废弃路径并更新 README

**状态：** completed

**Red Evidence：**

- Command: `mvn -q -Dtest=StockAnalysisWorkflowTest test`
- Actual: the pre-change graph did not write `ExecutionState.currentNode`, so the new assertion failed with `null`.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn -q test && git diff --check`
- Actual: PASS; `git diff --check` produced no output.

**涉及文件：**
- Modify: `README.md`
- Modify: `.ai/ISSUE-2026-08-27-graph-native-reflection/tasks.md`

1. 更新状态为 `in_progress`。
2. 删除未使用导入、占位逻辑和死代码，确认不存在图外 Reflector 调用。
3. 更新 README 的组件分层、条件路由和新闻补齐说明。
4. 运行 `mvn -q test` 与 `git diff --check`，记录 Green Evidence 并标记完成。
