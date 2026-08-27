# ISSUE_2026-08-27-recursive-short-term-summary 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 用模型递归压缩超限短期记忆，并把摘要作为独立系统上下文传给对话模型。

**架构：** Redis 保留最近原始消息，独立 Redis Key 保存递归摘要；无状态摘要 AI Service 根据旧摘要和淘汰消息产出替代摘要；主助手通过系统消息接收摘要和长期记忆。

**技术栈：** Java 21、Spring Boot、LangChain4j、Redis、JUnit 5、Mockito。

**相关文档：**
- 需求文档：`.ai/ISSUE-2026-08-27-recursive-short-term-summary/requirements.md`
- 设计文档：`.ai/ISSUE-2026-08-27-recursive-short-term-summary/design.md`

### Task 1: 覆盖递归摘要的失败测试

**状态：** completed

**Red Evidence：** `mvn -q test -Dtest=ShortTermSummaryServiceTest` 在测试使用双参数摘要器时编译失败，证明旧实现无法接收旧摘要。

**Green Evidence：** `mvn -q test -Dtest=ShortTermSummaryServiceTest` 通过。

**涉及文件：**
- Modify: `src/test/java/com/ljl/ai/agent/memoery/ShortTermSummaryServiceTest.java`
- Modify: `src/main/java/com/ljl/ai/agent/memoery/ShortTermSummaryService.java`

1. 编写测试，要求生成器收到旧摘要与淘汰消息，且生成结果替换旧摘要。
2. 运行 `mvn -q test -Dtest=ShortTermSummaryServiceTest`，记录 RED。
3. 实现最小递归摘要和预算校验。
4. 再运行同一命令，记录 GREEN 并标记完成。

### Task 2: 覆盖系统上下文注入的失败测试

**状态：** completed

**Red Evidence：** `mvn -q test -Dtest=ChatServiceMemoryContextTest` 因 `StockAnalysisAssistant` 缺少带记忆上下文的方法而编译失败。

**Green Evidence：** `mvn -q test -Dtest=ShortTermSummaryServiceTest,ChatServiceMemoryContextTest,ChatServiceToolLoopExceededTest` 通过。

**涉及文件：**
- Modify: `src/test/java/com/ljl/ai/agent/service/ChatServiceMemoryKeyTest.java`
- Modify: `src/main/java/com/ljl/ai/agent/service/ChatService.java`
- Modify: `src/main/java/com/ljl/ai/agent/agent/StockAnalysisAssistant.java`

1. 编写测试，要求当前用户消息保持原文，摘要通过助手上下文参数传递。
2. 运行相应测试并记录 RED。
3. 调整助手接口和 ChatService 调用。
4. 运行测试并记录 GREEN，标记完成。

### Task 3: 注册摘要模型并清理摘要 Key

**状态：** completed

**Red Evidence：** `mvn -q test -Dtest=ChatServiceMemoryKeyTest` 失败，显示未调用 `ShortTermSummaryService.delete`。

**Green Evidence：** `mvn -q test -Dtest=ChatServiceMemoryKeyTest` 通过。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/agent/agent/ConversationSummaryAssistant.java`
- Modify: `src/main/java/com/ljl/ai/agent/agent/AgentConfig.java`
- Modify: `src/main/java/com/ljl/ai/agent/service/ChatService.java`

1. 为摘要器 Bean 和会话删除摘要清理编写失败测试。
2. 运行聚焦测试并记录 RED。
3. 添加摘要 AI Service、Bean 与清理调用。
4. 运行聚焦测试并记录 GREEN，标记完成。

### Task 4: 回归验证

**状态：** completed

**Red Evidence：** 不适用

**Green Evidence：** `mvn -q test`、`mvn -q -DskipTests compile` 与 `git diff --check` 均通过。

1. 运行 `mvn -q test`。
2. 运行 `mvn -q -DskipTests compile`。
3. 运行 `git diff --check`，回写结果并标记完成。
