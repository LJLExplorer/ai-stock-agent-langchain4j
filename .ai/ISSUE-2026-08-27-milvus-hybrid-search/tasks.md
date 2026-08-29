# ISSUE-2026-08-27-milvus-hybrid-search 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 用 Milvus 2.5.4 的 BM25 Function 和 Hybrid Search 将知识库 RAG 从单路语义检索升级为 RRF 融合检索。

**架构：** 以 Milvus Java Client V2 建立包含文本、稠密向量和 BM25 稀疏向量的独立 collection。`RetrievalService` 发送稠密 ANN 与稀疏 BM25 两个请求，通过 Milvus `RRFRanker` 得到最终排名，并保留 MongoDB 的文档可见性过滤。

**技术栈：** Java 21、Spring Boot、Milvus 2.5.4、Milvus Java SDK 2.5.7、LangChain4j、JUnit 5、Mockito。

**相关文档：**

- 需求文档：`.ai/ISSUE-2026-08-27-milvus-hybrid-search/requirements.md`
- 设计文档：`.ai/ISSUE-2026-08-27-milvus-hybrid-search/design.md`

### Task 1: 建立 Milvus Hybrid Search 客户端边界与结果模型

**状态：** completed

**Red Evidence：**

- Command: `mvn -q -Dtest=MilvusHybridSearchResultTest test`
- Actual: test compilation failed because `MilvusHybridSearchResult` and `MilvusHybridSearchClient` did not exist.
- Match Expected: yes

**Green Evidence：**

- Command: `mvn -q -Dtest=MilvusHybridSearchResultTest test`
- Actual: PASS.

**涉及文件：**

- Create: `../../src/main/java/com/ljl/ai/rag/MilvusHybridSearchClient.java`
- Create: `../../src/main/java/com/ljl/ai/rag/MilvusHybridSearchResult.java`
- Modify: `../../src/main/java/com/ljl/ai/rag/RetrievalResult.java`
- Test: `../../src/test/java/com/ljl/ai/rag/MilvusHybridSearchResultTest.java`

1. 将本 Task 状态更新为 `in_progress`。
2. 编写失败测试：客户端为一个查询构建稠密 ANN 与 BM25 稀疏 ANN 请求，并使用 `RRFRanker(60)`；结果映射同时保留两个通道的分数和 RRF 分数。
3. 运行 `mvn -q -Dtest=MilvusHybridSearchClientTest test`，记录 Red Evidence。
4. 实现最小的可注入 Milvus Client V2 适配器与扩展的结果模型。
5. 重跑定向测试，记录 Green Evidence 并标记 `completed`。

### Task 2: 创建并维护 BM25 混合检索 collection

**状态：** in_progress

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**

- Create: `../../src/main/java/com/ljl/ai/rag/MilvusHybridCollectionManager.java`
- Modify: `../../src/main/java/com/ljl/ai/config/MilvusConfig.java`
- Modify: `../../src/main/java/com/ljl/ai/knowledge/KnowledgeService.java`
- Test: `../../src/test/java/com/ljl/ai/rag/MilvusHybridCollectionManagerTest.java`
- Test: `../../src/test/java/com/ljl/ai/knowledge/KnowledgeServiceTest.java`

1. 更新状态为 `in_progress`。
2. 编写失败测试：schema 包含主键、文本、稠密向量、稀疏向量与 BM25 Function；新建、同步、启用时为每个分块写入混合 collection，删除/禁用时删除对应主键。
3. 运行 `mvn -q -Dtest=MilvusHybridCollectionManagerTest,KnowledgeServiceTest test`，记录 Red Evidence。
4. 实现 collection 初始化、索引、加载和分块的幂等写删逻辑；将混合 collection 名称配置为独立值。
5. 重跑定向测试，记录 Green Evidence 并标记 `completed`。

### Task 3: 用 RRF 融合检索替换 RAG 单路语义召回

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**

- Modify: `../../src/main/java/com/ljl/ai/rag/RetrievalService.java`
- Modify: `../../src/main/java/com/ljl/ai/rag/RetrievalResult.java`
- Modify: `../../src/main/java/com/ljl/ai/config/KnowledgeConfig.java`
- Test: `../../src/test/java/com/ljl/ai/rag/RetrievalServiceTest.java`

1. 更新状态为 `in_progress`。
2. 编写失败测试：RRF 总分排序正确；禁用文档被过滤；BM25 失败时返回语义检索降级结果；`similarity` 保持为最终 RRF 分数。
3. 运行 `mvn -q -Dtest=RetrievalServiceTest test`，记录 Red Evidence。
4. 将 `RetrievalService` 改为调用混合客户端，保留上下文构建、知识来源转换和 MongoDB 可见性过滤。
5. 重跑定向测试，记录 Green Evidence 并标记 `completed`。

### Task 4: 验证 API、配置与端到端兼容性

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**

- Modify: `src/main/resources/application.yml`
- Modify: `README.md`
- Test: `../../src/test/java/com/ljl/ai/controller/RagControllerTest.java`

1. 更新状态为 `in_progress`。
2. 编写失败测试：`/api/rag/search` 返回 RRF 融合字段，非法请求校验行为不变。
3. 运行 `mvn -q -Dtest=RagControllerTest test`，记录 Red Evidence。
4. 添加混合 collection、RRF 常数与降级配置；更新 README 的检索说明。
5. 重跑定向测试，执行 `mvn -q test` 和 `git diff --check`，记录 Green Evidence 并标记 `completed`。
