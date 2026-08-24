# ISSUE-2026-08-24-code-review-fixes 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 修复代码审查发现的输入校验、会话权限、并发记忆、摘要、向量召回和股票市场映射问题，并补充回归测试。

**架构：** 在控制器和会话服务层校验用户归属及关闭状态；在 Redis 存储层使用原子快照替换；在摘要服务层限制摘要和上下文长度；在行情适配器中集中处理市场代码映射。保持现有 API 和持久化结构不变。

**技术栈：** Java 21、Spring Boot 3、JUnit 5、Mockito、LangChain4j、MongoDB、Redis。

**相关文档：**
- 需求文档：`.ai/ISSUE-2026-08-24-code-review-fixes/requirements.md`
- 设计文档：`.ai/ISSUE-2026-08-24-code-review-fixes/design.md`

**相关规范：**
- 当前仓库未提供 `yx-coder` 规范目录。

### Task 1: 修复反馈和知识库请求的类型校验

**状态：** completed

**Red Evidence：** 既有代码对非法字段直接类型强转，问题由静态审查确认；本次以回归实现为主。

**Green Evidence：** `mvn -q test`：通过。

**涉及文件：** `ChatController.java`、`KnowledgeController.java` 及对应测试。

### Task 2: 分离聊天模型提示与业务历史

**状态：** completed

**Red Evidence：** 既有实现把拼接后的内部 prompt 保存为业务用户消息；问题由代码审查确认。

**Green Evidence：** `mvn -q test`：通过。

**涉及文件：** `ChatService.java` 及服务测试。

### Task 3: 修复短期摘要更新一致性

**状态：** completed

**Red Evidence：** 既有实现先写 Redis 摘要再更新消息窗口，存在失败不一致路径；问题由代码审查确认。

**Green Evidence：** `mvn -q test`：通过。

**涉及文件：** `ShortTermSummaryService.java` 及摘要测试。

### Task 4: 修复长期记忆用户过滤召回

**状态：** completed

**Red Evidence：** 既有实现仅取 `topK * 5` 个共享向量候选后过滤用户，存在漏召回路径；问题由代码审查确认。

**Green Evidence：** `mvn -q test`：通过。

**涉及文件：** `LongTermMemoryService.java` 及长期记忆测试。

### Task 5: 增加会话归属和关闭状态校验

**状态：** completed

**Red Evidence：** 代码审查确认按 sessionId 取会话但未校验 userId，且 CLOSED 会话仍可继续发送。

**Green Evidence：** `ChatMemoryServiceTest` 覆盖跨用户和已关闭会话，`mvn -q test` 通过。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/agent/memoery/ChatMemoryService.java`
- Modify: `src/main/java/com/ljl/ai/agent/service/ChatService.java`
- Test: `src/test/java/com/ljl/ai/agent/memoery/ChatMemoryServiceTest.java`

### Task 6: 修复 Redis 会话并发覆盖

**状态：** completed

**Red Evidence：** 原实现 delete 后逐条写入，更新期间存在空窗口。

**Green Evidence：** Redis 消息快照改为 MULTI/EXEC 事务，并在 `ChatService` 对同一会话串行化请求；`mvn -q test` 通过。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/agent/memoery/RedisChatMemoryStore.java`
- Test: `src/test/java/com/ljl/ai/agent/memoery/RedisChatMemoryStoreTest.java`

### Task 7: 修复摘要增长和上下文截断策略

**状态：** completed

**Red Evidence：** 原实现持续追加未限制的摘要，并截取上下文开头，可能丢失最新对话。

**Green Evidence：** 增加 `summary-max-chars` 上限，摘要和对话上下文均保留最新部分；摘要回归测试通过。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/agent/memoery/ShortTermSummaryService.java`
- Modify: `src/main/java/com/ljl/ai/agent/service/ChatService.java`
- Test: `src/test/java/com/ljl/ai/agent/memoery/ShortTermSummaryServiceTest.java`

### Task 8: 修正股票市场代码映射

**状态：** completed

**Red Evidence：** 原实现将所有非沪市代码归为深市，北交所代码会被错误路由。

**Green Evidence：** 新增沪/深/北交所归类测试，`MarketDataClientTest` 通过。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/agent/data/MarketDataClient.java`
- Test: `src/test/java/com/ljl/ai/agent/data/MarketDataClientTest.java`

### Task 9: 全量验证并回写执行记录

**状态：** completed

**Red Evidence：** 全量回归前已完成生产代码修改和差异检查。

**Green Evidence：** `mvn -q test`：通过；`mvn -q -DskipTests compile` 通过；前端 `npm run build` 通过；`git diff --check` 通过。

**涉及文件：** 测试报告与 `changelog.md`。
