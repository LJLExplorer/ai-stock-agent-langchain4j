# ISSUE-2026-08-27-review-fixes 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 修复工作流可观测性、知识库跨存储一致性和北交所 Planner 支持。

**架构：** ChatService 将工作流状态显式映射为响应元数据；检索层以 Mongo 文档状态过滤向量命中；Planner 采用统一的 A 股市场归一化规则。

**技术栈：** Spring Boot、MongoTemplate、Milvus LangChain4j、JUnit 5、Mockito、React/Vite。

**相关文档：**
- 需求文档：`.ai/ISSUE-2026-08-27-review-fixes/requirements.md`
- 设计文档：`.ai/ISSUE-2026-08-27-review-fixes/design.md`

### Task 1: 回传工作流执行记录

**状态：** completed

**Red Evidence：** 旧实现没有将 `ExecutionTask` 映射到聊天响应，前端无法展示工作流工具调用。

**Green Evidence：** `mvn -q -DskipTests compile` 通过；工作流映射单元测试已添加并在定向测试中通过。

**涉及文件：**
- Modify: `../../src/main/java/com/ljl/ai/service/ChatService.java`
- Test: `../../src/test/java/com/ljl/ai/service/ChatServiceWorkflowTest.java`

### Task 2: 隔离不可检索的知识文档

**状态：** completed

**Red Evidence：** 旧测试断言禁用只保存一次；新流程保存两次以先建立检索屏障，定向测试如预期失败。

**Green Evidence：** 非沙箱定向 Maven 测试通过，覆盖启用保存失败时的向量补偿和禁用保存失败时的不删向量。

**涉及文件：**
- Modify: `../../src/main/java/com/ljl/ai/knowledge/KnowledgeService.java`
- Modify: `../../src/main/java/com/ljl/ai/rag/RetrievalService.java`
- Test: `../../src/test/java/com/ljl/ai/knowledge/KnowledgeServiceTest.java`

### Task 3: 统一 Planner 股票市场规则

**状态：** completed

**Red Evidence：** 北交所代码在旧规则下无法通过 `PlanValidator`。

**Green Evidence：** 非沙箱定向 Maven 测试通过，`830799` 与 `430047.BJ` 均标准化为 `.BJ`。

**涉及文件：**
- Modify: `../../src/main/java/com/ljl/ai/planner/PlanValidator.java`
- Test: `../../src/test/java/com/ljl/ai/planner/PlanValidatorTest.java`

### Task 4: 集成验证与最终提交

**状态：** completed

**Red Evidence：** 首次定向测试暴露旧禁用顺序断言与新安全流程不一致，已更新为顺序断言。

**Green Evidence：** `mvn -q -Dtest='com.ljl.ai.agent.planner.PlanValidatorTest,com.ljl.ai.agent.service.ChatServiceWorkflowTest,com.ljl.ai.agent.knowledge.KnowledgeServiceTest' test`、`mvn -q -DskipTests compile`、`git diff --check` 通过；`frontend/npm run build` 已通过。

**涉及文件：**
- Modify: `.ai/ISSUE-2026-08-27-review-fixes/tasks.md`
