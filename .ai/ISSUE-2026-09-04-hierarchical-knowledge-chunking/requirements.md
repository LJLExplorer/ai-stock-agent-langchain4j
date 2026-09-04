# 层级化知识分块与 Parent/Child 检索需求

## 背景

当前知识库使用 LangChain4j 通用递归分块器，将文档切成扁平 TextSegment。分块没有稳定的章节边界，也没有 Parent Section、标题路径、股票代码、年份和标签等完整元数据；混合检索命中后只能返回单个 Child，无法补充相邻上下文或 Parent 摘要。

## 目标

建立面向中文金融文档的四级层级切分与 Parent/Child 检索机制：以 Child Chunk 参与向量和 BM25 检索，命中后根据 Parent Section 组装相邻 Chunk、标题路径和摘要，在控制上下文长度的同时保留章节语义。

## 功能需求

### 1. 四级递进切分

1. 一级按标题和章节切分 Parent Section，支持 Markdown `#`～`######` 标题，以及 `第一章`、`一、`、`（一）`、`1.`、`1.1` 等常见中文章节编号。
2. 普通短独立行不得自动识别为标题，避免财报表格或指标名称误切。
3. 无可识别标题的文档以文档标题作为唯一根 Parent。
4. Parent 内依次按中文段落、中文句子、字符兜底切分 Child，且不得跨 Parent Section。
5. Child 总长度目标为 600～800 字符；后续 Child 包含 80～120 字符 Overlap，Overlap 计入 800 字符上限。
6. Overlap 优先选择完整句子，其次短段落，最后按字符截取。
7. Parent 尾部不足 600 字符时应优先向前合并；无法合并时允许短块，不得跨 Section 补齐。

### 2. 元数据继承

每个 Child 必须继承并持久化：

- 完整 `headingPath`
- `stockCode`
- `year`
- `tags`
- `documentId`
- `parentSectionId`
- Parent 内的 `chunkIndex` 与 `chunkCount`

`stockCode` 和 `year` 优先使用文档 metadata 的同名字段；缺失时从标题、heading 和正文推断；仍无法识别时保留空值，不阻断入库。

### 3. Parent Section 持久化与摘要

1. Parent Section 必须独立持久化，不得将 Parent 全文重复写入每个 Child。
2. Parent 保存标题路径、完整正文、正文长度、摘要、股票代码、年份、标签、章节顺序、Child 数量及 Child 原文区间。
3. Parent 正文不超过 1200 字符时，命中后返回完整 Parent。
4. Parent 正文超过 1200 字符时，在入库阶段生成约 400～600 字符的抽取式摘要，不调用 LLM。
5. 摘要由 `headingPath + 首个有效段落 + 2～3 个关键句` 组成；关键句优先选择包含财务指标、同比/环比、增长下降、估值和风险等信息的句子。
6. Parent 摘要只用于命中后的上下文扩展，不参与 Child Embedding。

### 4. Child 检索与上下文扩展

1. 仅 Child 参与向量及 BM25 检索。
2. 命中 Child 后，默认补充同一 Parent 内前后各 1 个相邻 Child；首尾只取实际存在的一侧。
3. 多个命中窗口重叠时合并去重，并按 `chunkIndex` 顺序拼接。
4. 拼接时必须消除 Child Overlap 造成的重复正文。
5. Parent 不超过 1200 字符时，同一 Parent 仅返回一次完整正文。
6. Parent 超过 1200 字符时，返回完整标题路径、Parent 摘要和合并后的 Child 窗口正文。
7. 不相交的命中窗口保持为同一 Parent 下的独立结果。
8. 相邻 Child 仅扩展上下文，不获得独立检索分数；窗口使用其中命中 Child 的最高 RRF 分数参与排序。
9. 最终 `topK` 按合并后的窗口数计算。

### 5. 一致性与兼容性

1. 手工新增、飞书同步、重新启用和启动回填必须复用同一个层级切分服务。
2. 每次入库使用新的 `ingestionVersion`；Parent、Child 和 Milvus 数据均携带版本。
3. 仅当本次 Parent 与全部 Child 写入成功后，才切换文档的活动版本并清理旧版本。
4. 写入失败时必须回滚本次版本数据，不得影响旧活动版本。
5. 禁用和删除时先关闭文档可见性，再清理向量及 Parent 数据。
6. 现有 RAG 查询与知识来源 API 保持兼容，可新增 Parent/窗口相关响应字段。

## 验收标准

- 标题层级解析正确，Child 不跨 Parent，且边界、长度、Overlap 符合上述规则。
- 所有 Child 完整继承 `headingPath + stockCode + year + tags`。
- Child 命中后能正确返回相邻窗口；重叠窗口合并、文本去重且按 `chunkIndex` 排序。
- 短 Parent 返回全文，长 Parent 返回 400～600 字符抽取式摘要及 Child 窗口。
- Parent 摘要不参与 Child Embedding。
- 同步过程不会暴露新旧版本混杂的数据，失败可回滚。
- Hybrid Search 故障或 Parent 数据缺失时可安全降级，不中断 RAG 对话。
- 定向测试、完整 Maven 测试和 `git diff --check` 通过。
