# ISSUE-2026-09-04-hierarchical-knowledge-chunking 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 将知识库升级为中文四级层级分块，以 Child 完成 Dense/BM25 检索，并按 Parent Section 扩展相邻 Chunk、标题和摘要。

**架构：** 新增统一层级分块服务，将 Parent Section 持久化到 MongoDB，将带完整元数据和 ingestion version 的 Child 写入现有语义向量库与新版 Milvus Hybrid Collection。检索先召回 Child，再按活动版本加载 Parent，合并相邻窗口并消除 Overlap 重复文本。

**技术栈：** Java 21、Spring Boot、Spring Data MongoDB、LangChain4j 1.0.0-beta3、Milvus Java SDK 2.5.7、JUnit 5、Mockito。

**相关文档：**

- 需求文档：`.ai/ISSUE-2026-09-04-hierarchical-knowledge-chunking/requirements.md`
- 设计文档：`.ai/ISSUE-2026-09-04-hierarchical-knowledge-chunking/design.md`

**相关规范：**

- 仓库未提供 `./yx-coder/AGENT.md`
- 仓库未提供 `./yx-coder/规范/架构规范.md`
- 仓库未提供 `./yx-coder/规范/编码规范.md`

**涉及组件：**

- MongoDB：现有 `MongoTemplate`，仓库未提供独立数据库访问组件文档
- Milvus：`MilvusHybridCollectionManager`、`MilvusHybridSearchClient`
- 向量模型：LangChain4j `EmbeddingModel` 与 `EmbeddingStore<TextSegment>`

## 通用执行协议

每个 Task 必须严格执行以下顺序，且一次只推进一个 Task：

1. 先把该 Task 的 `状态` 从 `pending` 改为 `in_progress`；此前禁止修改生产代码。
2. 只编写本 Task 指定的失败测试。
3. 运行指定命令，确认因缺失目标行为而失败，并填写 `Red Evidence` 的 Command、Actual、Match Expected。
4. 编写使该测试通过的最小实现，不顺带实现后续 Task。
5. 运行定向测试，填写 `Green Evidence`。
6. 将状态改为 `completed`；证据和状态未回写前禁止开始下一个 Task。
7. 只暂存本 Task 的文件，运行 `git diff --cached --check` 后提交。提交信息使用任务给出的 subject，并在 footer 追加 `YX-Client: codex`。

---

### Task 1: 建立层级分块配置与版本化 Parent 数据契约

**状态：** completed

**Red Evidence：** `mvn -q -Dtest=KnowledgeSectionTest test`（2026-09-04）：FAIL（testCompile）；`KnowledgeConfig.ChunkConfig` 缺少层级分块默认值访问器，且 `KnowledgeSection` / `ChunkSpan` 尚不存在，符合预期。

**Green Evidence：** `mvn -q -Dtest=KnowledgeSectionTest test`（2026-09-04）：PASS（2 tests, exit 0）。

**涉及文件：**

- Modify: `src/main/java/com/ljl/ai/config/KnowledgeConfig.java`
- Modify: `src/main/java/com/ljl/ai/model/entity/KnowledgeDocument.java`
- Create: `src/main/java/com/ljl/ai/model/entity/KnowledgeSection.java`
- Create: `src/test/java/com/ljl/ai/model/entity/KnowledgeSectionTest.java`

**交付行为：** 配置默认值和 MongoDB 数据结构能够完整表达 600～800 Child、80～120 Overlap、1200 短 Parent 阈值、400～600 摘要预算以及 ingestion version。

**步骤：**

1. 按通用协议将状态改为 `in_progress`。
2. 新建 `KnowledgeSectionTest`，断言 `KnowledgeConfig.ChunkConfig` 默认值为 `minSize=600`、`targetSize=700`、`maxSize=800`、`minOverlap=80`、`maxOverlap=120`、`shortParentThreshold=1200`、`summaryMinSize=400`、`summaryMaxSize=600`、`strategyVersion="hierarchical-v1"`；断言 `KnowledgeSection` 可保存 section/version/headingPath/content/summary/元数据和 `List<ChunkSpan>`。
3. 运行 `mvn -q -Dtest=KnowledgeSectionTest test`，预期因 `KnowledgeSection` 或配置访问器不存在而 FAIL，填写 Red Evidence。
4. 在 `KnowledgeConfig` 中扩展 `ChunkConfig`；在 `KnowledgeDocument` 增加 `activeIngestionVersion` 与 `chunkingStrategyVersion`；新增：

   ```java
   @Document(collection = "knowledge_sections")
   public class KnowledgeSection {
       @Id private String sectionRecordId;
       private String sectionId;
       private String documentId;
       private String ingestionVersion;
       private List<String> headingPath;
       private String content;
       private int contentLength;
       private String summary;
       private String stockCode;
       private String year;
       private List<String> tags;
       private int sectionIndex;
       private int childCount;
       private List<ChunkSpan> chunkSpans;
   }
   ```

   `ChunkSpan` 至少包含 `chunkId/chunkIndex/startOffset/endOffset/overlapStartOffset`，所有 offset 使用 Java String 的字符索引。
5. 重跑 `mvn -q -Dtest=KnowledgeSectionTest test`，预期 PASS，填写 Green Evidence 并标记完成。
6. 提交：`feat: 建立层级分块数据契约`。

---

### Task 2: 解析 Markdown 与中文标题并构建 Parent 层级

**状态：** completed

**Red Evidence：** `mvn -q -Dtest=HierarchicalDocumentChunkerTest#parsesHeadingHierarchy test`（2026-09-04）：FAIL（testCompile）；`HierarchicalDocumentChunker` 尚不存在，符合预期。审查修复：`mvn -q '-Dtest=HierarchicalDocumentChunkerTest#preservesLeadingContentAsDocumentTitleRootParent+usesDecimalSegmentCountAsHeadingLevel' test`（2026-09-04）：FAIL（2 failures）；首个标题前正文被丢弃，且 `1.2` 错误继承 `1.1/1.1.1` 路径，符合预期。

**Green Evidence：** `mvn -q -Dtest=HierarchicalDocumentChunkerTest test`（2026-09-04）：PASS（3 tests, exit 0）。审查修复后：`mvn -q -Dtest=HierarchicalDocumentChunkerTest test`（2026-09-04）：PASS（5 tests, exit 0）。

**涉及文件：**

- Create: `src/main/java/com/ljl/ai/knowledge/HierarchicalDocumentChunker.java`
- Create: `src/test/java/com/ljl/ai/knowledge/HierarchicalDocumentChunkerTest.java`

**交付行为：** 输入原始文档后，能按 Markdown 和中文章节编号构建具有完整 headingPath 的 Parent Section 草稿，不把普通短行或财务指标行误判为标题。

**步骤：**

1. 更新状态为 `in_progress`。
2. 编写测试覆盖 `# 年报 / ## 管理层讨论`、`第一章`、`一、`、`（一）`、`1.`、`1.1` 的层级变化；加入“营业收入”“毛利率 35%”短行作为误判反例；验证无标题文档以文档标题作为根 Parent。
3. 运行 `mvn -q -Dtest=HierarchicalDocumentChunkerTest#parsesHeadingHierarchy test`，预期因类或方法不存在而 FAIL，填写 Red Evidence。
4. 新增 Spring `@Component`，公开最小入口：

   ```java
   public List<ParentDraft> parseSections(String documentTitle, String rawContent,
                                           List<String> tags, Map<String, String> metadata)
   ```

   使用标题栈生成 `List<String> headingPath`；`ParentDraft` 保存正文在原文中的顺序、元数据和 sectionIndex。只有匹配完整章节模式的独立行才算中文标题。
5. 重跑定向测试，预期 PASS，填写 Green Evidence 并标记完成。
6. 提交：`feat: 解析中文文档标题层级`。

---

### Task 3: 实现段落、句子与字符三级 Child 切分

**状态：** completed

**Red Evidence：** 初始实现：`mvn -q '-Dtest=HierarchicalDocumentChunkerTest#splitsChildrenByParagraphSentenceAndCharacter+keepsOverlapWithinParent+allowsShortTailWhenMergeWouldOverflow' test`（2026-09-04）：FAIL（testCompile）；`HierarchicalDocumentChunker` 缺少 `ChunkedDocument`、`ChildDraft` 及 `chunk(KnowledgeDocument, String)`，符合预期。审查修复：`mvn -q -Dtest=HierarchicalDocumentChunkerTest#prioritizesParagraphsBeforeSentencesUnlessParagraphIsOverlong test`（2026-09-04）：FAIL（expected `<702>` but was `<700>`）；同一目标窗口内的句末被选为边界而非段落边界，符合预期。

**Green Evidence：** 初始实现：`mvn -q '-Dtest=HierarchicalDocumentChunkerTest#splitsChildrenByParagraphSentenceAndCharacter+keepsOverlapWithinParent+allowsShortTailWhenMergeWouldOverflow' test`（2026-09-04）：PASS（3 tests, exit 0）。审查修复：`mvn -q -Dtest=HierarchicalDocumentChunkerTest#prioritizesParagraphsBeforeSentencesUnlessParagraphIsOverlong test`（2026-09-04）：PASS（1 test, exit 0）。回归：`mvn -q -Dtest=HierarchicalDocumentChunkerTest test`（2026-09-04）：PASS（9 tests, exit 0）。

**涉及文件：**

- Modify: `src/main/java/com/ljl/ai/knowledge/HierarchicalDocumentChunker.java`
- Modify: `src/test/java/com/ljl/ai/knowledge/HierarchicalDocumentChunkerTest.java`

**交付行为：** 每个 Parent 内生成目标 600～800 字符、Overlap 80～120 字符的 Child，优先沿段落和句子边界切分，最终按字符兜底，且不跨 Parent。

**步骤：**

1. 更新状态为 `in_progress`。
2. 在现有测试中新增 `splitsChildrenByParagraphSentenceAndCharacter`、`keepsOverlapWithinParent`、`allowsShortTailWhenMergeWouldOverflow`：使用带唯一标记的段落、超长句和两个相邻 Parent，断言 chunkIndex 连续、常规块在 600～800、第二块起 overlap 在 80～120、字符硬切不超过 800、Parent 标记不串块。
3. 运行 `mvn -q -Dtest=HierarchicalDocumentChunkerTest#splitsChildrenByParagraphSentenceAndCharacter+keepsOverlapWithinParent+allowsShortTailWhenMergeWouldOverflow test`，预期 FAIL，填写 Red Evidence。
4. 在 `HierarchicalDocumentChunker` 增加：

   ```java
   public ChunkedDocument chunk(KnowledgeDocument document, String ingestionVersion)
   ```

   实现段落 token、句子 token、字符兜底；每个 Child 同时返回干净正文、Embedding 输入和 Parent 原文 offset。Overlap 计入 800 上限，短尾优先向前合并但不跨 Section。
5. 重跑定向测试，预期 PASS，填写 Green Evidence 并标记完成。
6. 提交：`feat: 实现中文层级子块切分`。

---

### Task 4: 继承金融元数据并生成抽取式 Parent 摘要

**状态：** completed

**Red Evidence：** 2026-09-04：运行 `mvn -q '-Dtest=HierarchicalDocumentChunkerTest#inheritsFinancialMetadata+createsExtractiveSummary' test`，因 `ParentDraft`/`ChildDraft` 尚无 stockCode、year、summary 与 tags 访问器而 testCompile 失败，符合 RED 预期。

**Green Evidence：** 2026-09-04：`mvn -q '-Dtest=HierarchicalDocumentChunkerTest#inheritsFinancialMetadata+createsExtractiveSummary' test` 通过；`mvn -q -Dtest=HierarchicalDocumentChunkerTest test` 通过；`git diff --check` 无输出。

**涉及文件：**

- Modify: `src/main/java/com/ljl/ai/knowledge/HierarchicalDocumentChunker.java`
- Modify: `src/test/java/com/ljl/ai/knowledge/HierarchicalDocumentChunkerTest.java`

**交付行为：** stockCode/year 按既定优先级推断并由所有 Child 继承；长 Parent 生成 400～600 字符的无 LLM 抽取式摘要，短 Parent 不生成摘要。

**步骤：**

1. 更新状态为 `in_progress`。
2. 新增测试：显式 metadata 覆盖标题/正文推断；metadata 缺失时能从标题、headingPath、正文依次识别；无法识别时为空；tags 完整继承。构造超过 1200 字符 Parent，断言摘要含 headingPath、首段和 2～3 个高分关键句，长度在 400～600，重复句去除且关键句按原文顺序；短 Parent 的 summary 为空。
3. 运行 `mvn -q -Dtest=HierarchicalDocumentChunkerTest#inheritsFinancialMetadata+createsExtractiveSummary test`，预期 FAIL，填写 Red Evidence。
4. 实现 metadata resolver 与确定性句子评分。关键词至少覆盖财务指标、同比/环比、增长/下降、估值、现金流、负债、风险/减值/诉讼；数值、百分比和金额单位加权。先选择候选，再按原文位置输出，最后在 600 字符内按句界截断。
5. 确保 `ChildDraft.embeddingText()` 只由 headingPath 前缀和 Child 正文组成，不含 Parent summary。
6. 重跑定向测试，预期 PASS，填写 Green Evidence 并标记完成。
7. 提交：`feat: 生成金融章节摘要与元数据`。

---

### Task 5: 持久化并按版本管理 Parent Section

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**

- Create: `src/main/java/com/ljl/ai/knowledge/KnowledgeSectionStore.java`
- Create: `src/test/java/com/ljl/ai/knowledge/KnowledgeSectionStoreTest.java`

**交付行为：** Parent 可按 document/version 批量保存、按 section/version 读取，并可只清理指定版本或整个文档。

**步骤：**

1. 更新状态为 `in_progress`。
2. 使用 Mockito `MongoTemplate` 编写失败测试，验证 `saveAll` 使用 `sectionRecordId = ingestionVersion + ":" + sectionId`，`find` 同时约束 `sectionId` 与 `ingestionVersion`，`deleteVersion` 与 `deleteDocument` 的 Query 不越界删除其他文档。
3. 运行 `mvn -q -Dtest=KnowledgeSectionStoreTest test`，预期因类不存在而 FAIL，填写 Red Evidence。
4. 实现：

   ```java
   void saveAll(List<KnowledgeSection> sections);
   Optional<KnowledgeSection> find(String sectionId, String ingestionVersion);
   void deleteVersion(String documentId, String ingestionVersion);
   void deleteDocument(String documentId);
   ```

5. 重跑定向测试，预期 PASS，填写 Green Evidence 并标记完成。
6. 提交：`feat: 持久化版本化父章节`。

---

### Task 6: 根据 Parent 原文区间合并命中窗口

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**

- Create: `src/main/java/com/ljl/ai/rag/ParentContextAssembler.java`
- Modify: `src/main/java/com/ljl/ai/rag/RetrievalResult.java`
- Create: `src/test/java/com/ljl/ai/rag/ParentContextAssemblerTest.java`

**交付行为：** 短 Parent 返回一次全文；长 Parent 对命中 Child 前后各扩 1 个、合并重叠窗口、按 chunkIndex 拼接并消除文本 Overlap。

**步骤：**

1. 更新状态为 `in_progress`。
2. 编写失败测试覆盖：首尾邻居裁剪；命中 2、3 时窗口合并；命中 1、6 时保留两个窗口；字符区间交集只输出一次；短 Parent 多 Child 命中仍只返回一个结果；结果按最高 RRF、命中数、最高 semanticScore、原始顺序排序并截取 topK。
3. 运行 `mvn -q -Dtest=ParentContextAssemblerTest test`，预期因 assembler 或新结果字段不存在而 FAIL，填写 Red Evidence。
4. 在 `RetrievalResult` 增加 `parentSectionId`、`headingPath`、`parentSummary`、`matchedChunkIds`、`windowStartIndex/windowEndIndex`。实现：

   ```java
   public List<RetrievalResult> assemble(List<ChildHit> hits,
                                         Map<SectionVersionKey, KnowledgeSection> sections,
                                         int topK)
   ```

   使用 ChunkSpan 的原文区间求并集，不通过字符串 contains 去重。
5. 重跑定向测试，预期 PASS，填写 Green Evidence 并标记完成。
6. 提交：`feat: 组装父章节命中窗口`。

---

### Task 7: 扩展新版 Milvus Hybrid Collection 的 Child schema

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**

- Modify: `src/main/java/com/ljl/ai/knowledge/MilvusHybridCollectionManager.java`
- Modify: `src/main/java/com/ljl/ai/config/MilvusConfig.java`
- Modify: `src/test/java/com/ljl/ai/knowledge/MilvusHybridCollectionManagerTest.java`

**交付行为：** 新 Hybrid Collection 为每个 Child 存储 Parent、版本、索引和金融元数据，并支持按 document/version 精确删除。

**步骤：**

1. 更新状态为 `in_progress`。
2. 扩展现有 schema 测试，断言字段包含 `ingestionVersion`、`parentSectionId`、`headingPath`、`chunkIndex`、`chunkCount`、`stockCode`、`year`、`tags`；upsert 行写入全部值；删除 filter 同时正确转义 documentId/version。
3. 运行 `mvn -q -Dtest=MilvusHybridCollectionManagerTest test`，预期 FAIL，填写 Red Evidence。
4. 将 `MilvusConfig.hybridCollectionName` 默认值切到 `stock_analysis_knowledge_hybrid_v2`；将 manager 的宽参数 `insert(...)` 改为接收一个明确的 `HybridChunkRow`；新增 `deleteDocumentVersion(documentId, ingestionVersion)`，保留 `deleteDocument(documentId)`。
5. 重跑定向测试，预期 PASS，填写 Green Evidence 并标记完成。
6. 提交：`feat: 扩展混合检索子块结构`。

---

### Task 8: 将 Parent/版本元数据映射到 Hybrid Search 命中

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**

- Modify: `src/main/java/com/ljl/ai/rag/MilvusHybridSearchClient.java`
- Modify: `src/main/java/com/ljl/ai/rag/MilvusHybridSearchResult.java`
- Modify: `src/test/java/com/ljl/ai/rag/MilvusHybridSearchResultTest.java`

**交付行为：** Hybrid Search 请求返回全部 Child 上下文字段，且候选数量由调用方控制，不在客户端提前按最终 topK 截断。

**步骤：**

1. 更新状态为 `in_progress`。
2. 在现有测试中断言 outFields 包含 Task 7 的新增字段，SearchResult entity 能映射 ingestionVersion、parentSectionId、headingPath、chunkIndex/chunkCount、stockCode/year/tags；缺失可选字段时使用安全空值。
3. 运行 `mvn -q -Dtest=MilvusHybridSearchResultTest test`，预期 FAIL，填写 Red Evidence。
4. 扩展 `MilvusHybridSearchResult` 字段；更新 `MilvusHybridSearchClient.search(query, embedding, candidateCount)` 的 outFields 和类型安全转换。headingPath/tags 使用 JSON 数组字符串解析，解析失败时记录告警并返回空列表。
5. 重跑定向测试，预期 PASS，填写 Green Evidence 并标记完成。
6. 提交：`feat: 返回混合检索层级元数据`。

---

### Task 9: 统一写入 Parent 与两套 Child 向量并支持回滚

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**

- Create: `src/main/java/com/ljl/ai/knowledge/KnowledgeIngestionService.java`
- Modify: `src/main/java/com/ljl/ai/knowledge/KnowledgeService.java`
- Create: `src/test/java/com/ljl/ai/knowledge/KnowledgeIngestionServiceTest.java`
- Modify: `src/test/java/com/ljl/ai/knowledge/KnowledgeServiceTest.java`

**交付行为：** 新增/同步/启用使用同一份 ChunkedDocument，同时写 Parent、EmbeddingStore 和 Hybrid Collection；全部成功才返回可发布版本，任一失败都补偿本次写入。

**步骤：**

1. 更新状态为 `in_progress`。
2. 编写 `KnowledgeIngestionServiceTest`：成功时每个 Child 只调用一次 embedding，`embeddingModel.embed` 输入含 headingPath 但 `TextSegment.text()` 为干净正文；EmbeddingStore 与 hybrid manager 接收相同 chunkId/metadata；中间 Child 失败时删除本版本已写向量和 Parent。扩展 `KnowledgeServiceTest`，断言新增、同步、启用均委托 ingestion service，并在 Mongo 文档保存成功后设置 activeIngestionVersion。
3. 运行 `mvn -q -Dtest=KnowledgeIngestionServiceTest,KnowledgeServiceTest test`，预期 FAIL，填写 Red Evidence。
4. 新增 `KnowledgeIngestionService.ingest(KnowledgeDocument)`，返回 `IngestionResult(version, vectorIds, chunkCount)`。生成 UUID ingestionVersion，调用 chunker、section store、embedding store 与 hybrid manager。把 `KnowledgeService.processAndStoreDocument` 的职责迁入该服务。
5. 发布顺序：先写新 Parent/Child，再由 `KnowledgeService` 保存 `activeIngestionVersion`；Mongo 保存失败时调用 `rollback(result)`。旧 vectorIds 在发布成功后清理。
6. 重跑定向测试，预期 PASS，填写 Green Evidence 并标记完成。
7. 提交：`feat: 统一层级知识入库流程`。

---

### Task 10: 统一回填、禁用、删除与旧版本清理

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**

- Modify: `src/main/java/com/ljl/ai/knowledge/HybridKnowledgeBackfill.java`
- Modify: `src/main/java/com/ljl/ai/knowledge/KnowledgeService.java`
- Create: `src/test/java/com/ljl/ai/knowledge/HybridKnowledgeBackfillTest.java`
- Modify: `src/test/java/com/ljl/ai/knowledge/KnowledgeServiceTest.java`

**交付行为：** 启动回填不再独立切分；切分策略版本落后时才重建；禁用/删除先关闭可见性，再清理 Parent 和两套 Child 数据。

**步骤：**

1. 更新状态为 `in_progress`。
2. 编写回填测试：活动文档缺少 `hierarchical-v1` 时调用统一 ingestion 并发布版本；策略版本一致时跳过；单篇失败不影响后续文档。扩展 KnowledgeService 测试，验证 disable/delete 对 section store 与 hybrid manager 的清理顺序，以及旧 ingestion version 清理失败不撤回已发布新版本。
3. 运行 `mvn -q -Dtest=HybridKnowledgeBackfillTest,KnowledgeServiceTest test`，预期 FAIL，填写 Red Evidence。
4. 让 `HybridKnowledgeBackfill` 只负责筛选待迁移文档并调用统一入库入口，删除 `DocumentSplitters.recursive`。在 `KnowledgeService` 完成发布后调用 `deleteVersion` 清理旧 Parent；禁用/删除调用 `deleteDocument` 清理全部 Parent/Hybrid 行。
5. 清理失败只记录可重试错误；检索以 activeIngestionVersion 过滤保证正确性。
6. 重跑定向测试，预期 PASS，填写 Green Evidence 并标记完成。
7. 提交：`feat: 统一知识回填与生命周期清理`。

---

### Task 11: 将 Child 检索结果扩展为 Parent 上下文窗口

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**

- Modify: `src/main/java/com/ljl/ai/rag/RetrievalService.java`
- Modify: `src/test/java/com/ljl/ai/rag/RetrievalServiceTest.java`

**交付行为：** Hybrid 与语义降级都按活动 ingestion version 过滤 Child，并经 ParentContextAssembler 返回最终 topK；Parent 缺失时安全返回命中 Child。

**步骤：**

1. 更新状态为 `in_progress`。
2. 扩展现有测试：请求 topK=5 时 Hybrid 候选数为 15；仅活动版本 Child 可见；命中交给 assembler 后再截取最终 topK；EmbeddingStore 语义降级读取相同 metadata；Parent 缺失时内容等于原 Child 且保留 headingPath；禁用/删除中文档仍被过滤。
3. 运行 `mvn -q -Dtest=RetrievalServiceTest test`，预期 FAIL，填写 Red Evidence。
4. 将 `enabledDocumentIds` 改为返回 `documentId -> activeIngestionVersion`；去除仅按 `(documentId, content)` 判断版本的逻辑，语义复核键至少包含 ingestionVersion/chunkId。调用 `KnowledgeSectionStore` 批量加载命中 Parent，再调用 assembler。
5. `buildAugmentedContext` 与 `toKnowledgeSources` 保持兼容，使用组装后的 `RetrievalResult.content`；Parent 缺失仅记录标识和错误类型，不记录正文。
6. 重跑定向测试，预期 PASS，填写 Green Evidence 并标记完成。
7. 提交：`feat: 扩展检索命中的父章节上下文`。

---

### Task 12: 更新配置、文档并完成 API 回归验证

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**

- Modify: `src/main/resources/application.yml`
- Modify: `README.md`
- Create: `src/test/java/com/ljl/ai/controller/RagControllerTest.java`

**交付行为：** 默认配置启用已确认的阈值和新版 collection，现有 RAG API 返回扩展内容且保持既有字段兼容，README 准确描述 Parent/Child 数据流。

**步骤：**

1. 更新状态为 `in_progress`。
2. 新建 Controller 回归测试，验证 `/api/rag/search` 或仓库实际公开的 RAG 检索接口仍返回原字段，并可序列化新增 Parent/窗口字段；非法请求行为保持不变。
3. 运行 `mvn -q -Dtest=RagControllerTest test`，预期因新断言尚未满足而 FAIL，填写 Red Evidence。
4. 更新 application.yml：Hybrid collection 使用 `stock_analysis_knowledge_hybrid_v2`，chunk 配置显式写出 600/700/800、80/120、1200、400/600、`hierarchical-v1` 和 retrieval candidate multiplier 3。
5. 更新 README 的混合 RAG、数据一致性、目录结构与已知限制，说明 Child 检索、Parent 扩展、无 LLM 摘要及版本发布。
6. 重跑 `mvn -q -Dtest=RagControllerTest test`，预期 PASS。
7. 运行 `mvn -q -Dtest=HierarchicalDocumentChunkerTest,KnowledgeSectionStoreTest,ParentContextAssemblerTest,MilvusHybridCollectionManagerTest,MilvusHybridSearchResultTest,KnowledgeIngestionServiceTest,HybridKnowledgeBackfillTest,KnowledgeServiceTest,RetrievalServiceTest,RagControllerTest test`，预期 PASS。
8. 运行 `mvn -q test`，预期 PASS；运行 `git diff --check`，预期无输出。将三条结果全部写入 Green Evidence，标记完成。
9. 提交：`docs: 完成层级知识检索配置与验证`。

---

## 完成定义

- 12 个 Task 均为 `completed`，每个都有真实 Red/Green Evidence。
- 所有生产入口不再直接调用 `DocumentSplitters.recursive`。
- MongoDB Parent、EmbeddingStore Child、Hybrid Collection Child 使用同一 ingestion version 和同一切分结果。
- Child 只负责检索，最终上下文严格遵循短 Parent 全文、长 Parent 摘要加相邻窗口规则。
- 完整测试与 `git diff --check` 通过，工作区无未说明变更。
