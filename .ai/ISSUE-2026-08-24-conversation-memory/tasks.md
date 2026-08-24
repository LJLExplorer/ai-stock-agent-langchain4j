# ISSUE_2026-08-24-conversation-memory 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 构建 Redis 短期记忆、滚动摘要、用户主动维护的长期记忆，以及轻量 RAG 诊断链路。

**架构：** LangChain4j ChatMemory 通过自定义 Redis List Store 保存短期消息；独立摘要服务按字符阈值压缩历史。长期记忆使用 MongoDB 元数据 + Milvus 向量，RAG 诊断使用 MongoDB 保存最小可判别字段并尽力写入。

**技术栈：** Java 21、Spring Boot 3.3、Spring Data Redis、Spring Data MongoDB、LangChain4j、Milvus、JUnit 5、Mockito。

**相关文档：**
- 需求文档：`.ai/ISSUE-2026-08-24-conversation-memory/requirements.md`
- 设计文档：`.ai/ISSUE-2026-08-24-conversation-memory/design.md`

**相关规范：**
- 架构规范：`@./yx-coder/规范/架构规范.md`（当前仓库不存在，按现有项目结构执行）
- 编码规范：`@./yx-coder/规范/编码规范.md`（当前仓库不存在，按现有 Java/Spring 风格执行）

**涉及组件：**
- 数据库：现有 MongoDB Spring Data 访问方式
- 缓存：Spring Data Redis `StringRedisTemplate`
- 向量库：现有 `EmbeddingStore<TextSegment>` 和 `EmbeddingModel`

## 执行规则

每个 Task 必须严格按以下顺序执行：

1. 回写任务状态为 `in_progress`。
2. 编写失败测试，不修改生产代码。
3. 运行测试并回写 `Red Evidence`。
4. 仅在失败原因符合预期后编写最小生产实现。
5. 运行测试并回写 `Green Evidence`。
6. 将状态回写为 `completed`。
7. 只提交本 Task 涉及的文件。

### Task 1: 引入 Redis 依赖和记忆配置

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Modify: `pom.xml`
- Create: `src/main/java/com/ljl/ai/agent/config/MemoryConfig.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/ljl/ai/agent/config/MemoryConfigTest.java`

**步骤 0：** 更新状态为 `in_progress`，保存任务文档。

**步骤 1：编写失败测试**

测试配置绑定 `memory.short-term.max-chars`、`max-messages`、`ttl` 和 `memory.long-term.top-k`。

**步骤 2：运行 RED**

Run: `mvn test -Dtest=MemoryConfigTest`

Expected: FAIL，因为配置类和配置项尚未存在。

回写实际命令、输出和 `Match Expected`。

**步骤 3：最小实现**

增加 `spring-boot-starter-data-redis`，创建类型安全的 `MemoryConfig`，在主配置中增加 Redis 和 memory 配置。

**步骤 4：运行 GREEN**

Run: `mvn test -Dtest=MemoryConfigTest`

Expected: PASS。

**步骤 5：** 回写证据并将状态改为 `completed`。

**步骤 6：** 提交：`git commit -m "feat: 添加记忆配置"`。

### Task 2: 实现 Redis List 短期消息存储

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/agent/memoery/RedisChatMemoryStore.java`
- Test: `src/test/java/com/ljl/ai/agent/memoery/RedisChatMemoryStoreTest.java`

**步骤 0：** 更新状态为 `in_progress`。

**步骤 1：编写失败测试**

使用 Mockito 验证消息按顺序写入 Redis List、可以反序列化读取、删除指定 Key，并设置 TTL；测试两个 memoryId 不互相读取。

**步骤 2：运行 RED**

Run: `mvn test -Dtest=RedisChatMemoryStoreTest`

Expected: FAIL，因为 Store 尚未存在。

**步骤 3：最小实现**

实现 LangChain4j `ChatMemoryStore`，使用 List 的 `delete + rightPush` 更新策略，保留所有 LangChain4j 消息类型，并对 memoryId 做 Key 编码。

**步骤 4：运行 GREEN**

Run: `mvn test -Dtest=RedisChatMemoryStoreTest`

Expected: PASS。

**步骤 5：** 回写证据并标记完成。

**步骤 6：** 提交：`git commit -m "feat: 使用Redis保存短期记忆"`。

### Task 3: 实现字符窗口和滚动摘要

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/agent/memoery/ShortTermSummaryService.java`
- Test: `src/test/java/com/ljl/ai/agent/memoery/ShortTermSummaryServiceTest.java`

**步骤 0：** 更新状态为 `in_progress`。

**步骤 1：编写失败测试**

覆盖未超 32K 不摘要、超限后生成摘要并保留窗口后半部分、摘要进度避免重复处理，以及摘要异常保留原窗口。

**步骤 2：运行 RED**

Run: `mvn test -Dtest=ShortTermSummaryServiceTest`

Expected: FAIL，因为摘要服务尚未存在。

**步骤 3：最小实现**

使用 Redis String 保存摘要和处理进度；按字符数触发；将历史超出部分与窗口前半部分合并为摘要；仅保留摘要和最新窗口后半部分。

**步骤 4：运行 GREEN**

Run: `mvn test -Dtest=ShortTermSummaryServiceTest`

Expected: PASS。

**步骤 5：** 回写证据并标记完成。

**步骤 6：** 提交：`git commit -m "feat: 添加短期记忆滚动摘要"`。

### Task 4: 接入 LangChain4j 会话窗口和用户会话 Key

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/agent/memoery/MongoChatMemoryProvider.java`
- Modify: `src/main/java/com/ljl/ai/agent/service/ChatService.java`
- Test: `src/test/java/com/ljl/ai/agent/memoery/MongoChatMemoryProviderTest.java`
- Test: `src/test/java/com/ljl/ai/agent/service/ChatServiceMemoryKeyTest.java`

**步骤 0：** 更新状态为 `in_progress`。

**步骤 1：编写失败测试**

验证 Provider 使用配置的窗口大小和 Redis Store；验证 Agent、工具调用收集和异常清理均使用 `userId:sessionId`，且摘要会作为当前轮上下文而不是持久消息重复写入。

**步骤 2：运行 RED**

Run: `mvn test -Dtest=MongoChatMemoryProviderTest,ChatServiceMemoryKeyTest`

Expected: FAIL，因为当前基线使用 Mongo Store 和纯 sessionId。

**步骤 3：最小实现**

替换 Provider 的 Store；在 ChatService 中统一构造 memoryId，并接入摘要读取/刷新；保持业务展示消息继续保存 MongoDB。

**步骤 4：运行 GREEN**

Run: `mvn test -Dtest=MongoChatMemoryProviderTest,ChatServiceMemoryKeyTest`

Expected: PASS。

**步骤 5：** 回写证据并标记完成。

**步骤 6：** 提交：`git commit -m "feat: 接入Redis会话窗口"`。

### Task 5: 实现长期记忆模型和向量服务

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/agent/model/entity/UserLongTermMemory.java`
- Create: `src/main/java/com/ljl/ai/agent/service/LongTermMemoryService.java`
- Create: `src/main/java/com/ljl/ai/agent/model/dto/LongTermMemoryRequest.java`
- Test: `src/test/java/com/ljl/ai/agent/service/LongTermMemoryServiceTest.java`

**步骤 0：** 更新状态为 `in_progress`。

**步骤 1：编写失败测试**

Mock EmbeddingModel、EmbeddingStore 和 MongoTemplate，验证主动录入、按用户过滤召回、topK 限制和删除时的用户归属校验。

**步骤 2：运行 RED**

Run: `mvn test -Dtest=LongTermMemoryServiceTest`

Expected: FAIL，因为实体和服务尚未存在。

**步骤 3：最小实现**

创建 Mongo 实体和 DTO；实现 Embedding 写入 Milvus、元数据写入 Mongo、候选检索后的 userId/enabled 过滤，以及向量和元数据删除。

**步骤 4：运行 GREEN**

Run: `mvn test -Dtest=LongTermMemoryServiceTest`

Expected: PASS。

**步骤 5：** 回写证据并标记完成。

**步骤 6：** 提交：`git commit -m "feat: 添加用户长期记忆"`。

### Task 6: 暴露长期记忆管理 API

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/agent/controller/LongTermMemoryController.java`
- Test: `src/test/java/com/ljl/ai/agent/controller/LongTermMemoryControllerTest.java`

**步骤 0：** 更新状态为 `in_progress`。

**步骤 1：编写失败测试**

使用 MockMvc 验证 POST 新增、GET 列表、GET 语义召回和 DELETE 删除接口的参数校验与状态码。

**步骤 2：运行 RED**

Run: `mvn test -Dtest=LongTermMemoryControllerTest`

Expected: FAIL，因为 Controller 尚未存在。

**步骤 3：最小实现**

增加 `/api/memories` Controller，委托 LongTermMemoryService，不在 Controller 重复实现用户隔离逻辑。

**步骤 4：运行 GREEN**

Run: `mvn test -Dtest=LongTermMemoryControllerTest`

Expected: PASS。

**步骤 5：** 回写证据并标记完成。

**步骤 6：** 提交：`git commit -m "feat: 添加长期记忆管理接口"`。

### Task 7: 增加轻量 RAG 诊断记录

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/agent/model/entity/RagTrace.java`
- Create: `src/main/java/com/ljl/ai/agent/service/RagTraceService.java`
- Modify: `src/main/java/com/ljl/ai/agent/rag/RagPipelineService.java`
- Test: `src/test/java/com/ljl/ai/agent/service/RagTraceServiceTest.java`

**步骤 0：** 更新状态为 `in_progress`。

**步骤 1：编写失败测试**

验证无召回、低分召回、正常召回时统计字段正确；验证 Mongo 写入异常只记录日志、不向主流程抛出异常。

**步骤 2：运行 RED**

Run: `mvn test -Dtest=RagTraceServiceTest`

Expected: FAIL，因为诊断实体和服务尚未存在。

**步骤 3：最小实现**

增加轻量 Mongo 实体和尽力写入服务；在 RAG 管道完成检索后记录 count/topScore/source IDs/titles/contextLength，并允许 ChatService 在模型完成后补写 answerLength、success 和 factCheckConfidence。

**步骤 4：运行 GREEN**

Run: `mvn test -Dtest=RagTraceServiceTest`

Expected: PASS。

**步骤 5：** 回写证据并标记完成。

**步骤 6：** 提交：`git commit -m "feat: 添加轻量RAG诊断"`。

### Task 8: 集成全部记忆与诊断链路

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/agent/service/ChatService.java`
- Test: `src/test/java/com/ljl/ai/agent/service/ChatServiceIntegrationTest.java`

**步骤 0：** 更新状态为 `in_progress`。

**步骤 1：编写失败测试**

验证对话会把摘要和长期记忆注入本轮问题；长期记忆召回失败时仍调用 Agent；RAG 诊断写入失败时仍返回成功响应；工具调用 ID 仍能正确收集。

**步骤 2：运行 RED**

Run: `mvn test -Dtest=ChatServiceIntegrationTest`

Expected: FAIL，因为基线尚未接入三类记忆/诊断协作。

**步骤 3：最小实现**

按设计顺序接入短期摘要、长期记忆和 RAG Trace，控制上下文边界，确保诊断服务异常不阻断主流程。

**步骤 4：运行 GREEN**

Run: `mvn test -Dtest=ChatServiceIntegrationTest`

Expected: PASS。

**步骤 5：** 回写证据并标记完成。

**步骤 6：** 提交：`git commit -m "feat: 完成会话记忆与RAG诊断集成"`。

### Task 9: 全量验证与文档更新

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Modify: `.ai/ISSUE-2026-08-24-conversation-memory/changelog.md`
- Modify: `.ai/ISSUE-2026-08-24-conversation-memory/tasks.md`
- Test: `src/test/**`

**步骤 0：** 更新状态为 `in_progress`。

**步骤 1：编写失败测试**

无需新增测试；先运行全量验证以捕获集成问题。

**步骤 2：运行 RED/基线验证**

Run: `mvn test`

Expected: 若环境依赖 Redis/Mongo/Milvus，连接测试可能失败；必须区分外部服务不可用与代码测试失败，并记录实际证据。

**步骤 3：最小修复**

仅修复本 Issue 引入的编译、测试和配置问题，不扩大功能范围。

**步骤 4：运行 GREEN**

Run: `mvn test && mvn -q -DskipTests compile`

Expected: PASS，或明确记录需要外部基础设施的测试并保留可运行的单元测试结果。

**步骤 5：** 回写所有任务证据，更新变更历史和任务状态。

**步骤 6：** 提交：`git commit -m "test: 完成会话记忆与RAG诊断验证"`。

## 交接

计划已完成，并保存到 `.ai/ISSUE-2026-08-24-conversation-memory/tasks.md`。

执行方式：

1. 子代理驱动（本会话）
2. 并行会话（独立）
