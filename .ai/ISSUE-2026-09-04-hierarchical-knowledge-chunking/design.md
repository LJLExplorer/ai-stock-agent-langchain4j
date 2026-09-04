# 层级化知识分块与 Parent/Child 检索设计

## 1. 总体架构

采用“自定义层级切分器 + Parent 独立持久化 + Child 向量检索”的方案：

```text
原始文档
  -> 标题层级解析
  -> Parent Section 持久化
  -> 段落/句子/字符递进切分 Child
  -> Child Embedding
  -> 写入语义向量库与 Milvus Hybrid Collection

查询
  -> Child Hybrid Search
  -> 活动版本与文档可见性过滤
  -> Parent/相邻 Child 扩展
  -> 窗口合并和正文去重
  -> 最终 topK 上下文
```

所有入库入口统一调用同一个层级分块服务，避免当前 `KnowledgeService` 与 `HybridKnowledgeBackfill` 各自切分造成数据不一致。

## 2. 数据模型

### 2.1 KnowledgeDocument 扩展

在现有文档元数据上增加：

- `activeIngestionVersion`：当前对检索可见的数据版本。
- 可选的切分策略版本，供后续无损回填判断使用。

### 2.2 Parent Section

新增 MongoDB 集合 `knowledge_sections`。每个 Parent Section 独立保存：

- `sectionRecordId`：物理记录主键，包含 ingestion version。
- `sectionId`：逻辑 Parent 标识，由 `documentId + sectionIndex` 稳定生成。
- `documentId`、`ingestionVersion`
- `headingPath`
- `content`、`contentLength`
- `summary`
- `stockCode`、`year`、`tags`
- `sectionIndex`、`childCount`
- Child 的 `chunkIndex`、正文起止位置及 Overlap 起止位置。

Parent 仅保存一份完整正文。Child 的字符区间用于从 Parent 原文重建命中窗口并消除 Overlap，不在 MongoDB 重复保存 Child 文本。

### 2.3 Child Chunk

两套向量存储中的每个 Child 统一携带：

- `chunkId`
- `documentId`
- `ingestionVersion`
- `parentSectionId`
- `headingPath`
- `chunkIndex`、`chunkCount`
- `stockCode`、`year`、`tags`
- Child 正文

Milvus 中 `tags` 可使用 JSON 数组字符串存入 VARCHAR 字段。新版字段通过新的 Hybrid Collection/schema 版本上线，避免修改已存在且不兼容的 collection。

## 3. 标题解析与 Parent 构建

标题解析器支持：

- Markdown ATX 标题 `#`～`######`。
- 中文章节序号：`第一章`、`一、`、`（一）`、`1.`、`1.1` 等。

解析器维护标题栈，遇到同级或更高级标题时弹出对应路径，再生成完整 `headingPath`。普通短独立行不作为标题，防止将表格单元、财务指标名称误判为章节。无标题文档使用文档标题构建根 Parent。

`stockCode` 和 `year` 的解析优先级为：文档 metadata 显式值 > 文档标题 > headingPath > Parent 正文。无法识别时保存为空值。`tags` 原样继承文档标签。

## 4. 四级 Child 切分

每个 Parent 内独立执行，禁止跨 Parent：

1. 按空行等中文段落边界累积正文。
2. 单段超过容量时，按 `。！？；` 等中文句末标点切分。
3. 单句仍超过容量时按字符硬切。
4. Child 总长度目标为 600～800 字符。

从第二个 Child 开始，先从前一个 Child 尾部选择 80～120 字符的 Overlap，再加入约 600～700 个新字符，使总长度不超过 800。Overlap 优先保持完整句子，其次短段落，最终按字符截取。

Parent 末尾短块优先与前块合并；若合并会超过上限则保留短块。切分结果记录 Child 在 Parent 原文中的区间，确保检索扩展时能按原文区间做无损合并。

Embedding 的输入使用轻量标题前缀增强章节语义：

```text
[标题路径] 年报 > 第三章 管理层讨论 > 盈利能力
[正文] <Child 正文>
```

对外返回的 Child 正文保持干净，不重复混入标题。Parent 摘要不加入 Embedding。

## 5. Parent 抽取式摘要

- `contentLength <= 1200`：不需要生成独立摘要，检索扩展直接使用 Parent 全文。
- `contentLength > 1200`：入库阶段生成 400～600 字符的抽取式摘要，不调用 LLM。

摘要由完整 headingPath、首个有效段落和 2～3 个关键句组成。关键句评分优先考虑：

- 财务指标与金额、百分比等数值。
- 同比、环比以及增长、下降、改善、恶化等趋势。
- 估值、盈利、现金流、负债等分析信息。
- 风险、不确定性、减值、诉讼等风险提示。

去除重复句后按原文顺序组织，并在字符预算内截断。摘要只在命中后的上下文扩展阶段使用。

## 6. 检索与窗口重组

Milvus 的初始候选数取请求 `topK` 的 3 倍。Child 命中后：

1. 按 `documentId + ingestionVersion` 校验文档启用状态和活动版本。
2. 根据 `parentSectionId + ingestionVersion` 读取 Parent。
3. 短 Parent：同一 Parent 去重后直接返回完整正文。
4. 长 Parent：将每个命中扩展为同 Parent 内 `[chunkIndex - 1, chunkIndex + 1]`。
5. 对相交窗口求并集；不相交窗口保持独立。
6. 根据 Child 原文区间按 `chunkIndex` 顺序重建正文，合并相交字符区间，消除 Overlap 重复文本。
7. 输出 `headingPath + Parent 摘要 + Child 窗口正文`。
8. 按合并窗口截取最终 `topK`。

相邻 Child 仅补充上下文，不产生检索得分。窗口使用其中命中 Child 的最高 RRF 分数；同分时依次比较命中 Child 数、最高语义分数和原始检索顺序。

`RetrievalResult` 保留现有字段，并增加：

- `parentSectionId`
- `headingPath`
- `parentSummary`
- `matchedChunkIds`
- `windowStartIndex`、`windowEndIndex`

`content` 保存最终组装后的上下文正文，现有调用方无需改变即可获得扩展上下文。

## 7. 版本一致性与生命周期

每次新增、同步、启用或回填生成新的 `ingestionVersion`：

1. 解析并准备完整 Parent/Child 数据。
2. 写入带新版本的 Parent。
3. 写入带新版本的全部语义向量和 Hybrid Search 行。
4. 全部成功后更新 `KnowledgeDocument.activeIngestionVersion`。
5. 活动版本切换成功后清理旧 Parent 和旧向量。

检索仅接受活动版本，因此写入期间的新数据和尚未清理的旧数据都不会造成混杂。任意写入阶段失败时，删除本次版本已写入的数据，不切换活动版本。

禁用和删除时先将文档设为不可见，再清理两套向量及 Parent。启动回填不得用旧 `chunkCount` 直接判断跳过，而应根据切分策略/schema 版本判断是否需要重建。

## 8. 错误处理与降级

- 标题无法识别：整篇使用根 Parent。
- `stockCode/year` 无法推断：保留空值。
- Parent 数据缺失：返回 Milvus 中的命中 Child 与 headingPath，不扩展邻居，并记录告警。
- Hybrid Search 失败：沿用单路语义检索降级，并依据相同 Child 元数据尝试 Parent 扩展。
- Parent/向量部分写入失败：补偿删除本次 ingestion version 数据，旧活动版本继续服务。
- 清理旧版本失败：记录可重试清理任务；由于活动版本过滤，不影响检索正确性。

## 9. 测试策略

### 单元测试

- Markdown 与中文章节层级、标题栈和误判保护。
- 段落、句子、字符切分优先级。
- 600～800 长度、80～120 Overlap、短尾块及 Parent 边界。
- `headingPath/stockCode/year/tags` 继承和推断优先级。
- 400～600 字符摘要、关键句加权、去重及顺序。
- 短 Parent 全文、长 Parent 摘要加相邻块。
- 多命中窗口合并、Overlap 文本去重、chunkIndex 顺序和最终 topK。

### 服务与集成测试

- 各入库入口复用统一切分服务。
- ingestion version 发布、失败回滚和旧版本清理。
- 禁用、删除和重新启用生命周期。
- 新 Hybrid Collection schema 及全部 Child 元数据。
- Hybrid Search 和语义降级均能扩展 Parent 上下文。
- 现有 RAG Controller、RAG Pipeline 和知识来源响应兼容。

### 完成检查

- 运行全部定向测试。
- 运行 `mvn -q test`。
- 运行 `git diff --check`。
