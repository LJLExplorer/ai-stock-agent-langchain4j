# Milvus 混合检索设计

## 架构

将 RAG 检索从 LangChain4j `EmbeddingStore` 单路查询迁移到 Milvus Java Client。Milvus collection 为每个知识分块保存 `text`、`dense_vector`、`sparse_vector` 和来源元数据；`sparse_vector` 由 Milvus 2.5.4 的内置 `BM25` Function 从 `text` 自动生成。

```text
查询文本
  |-- EmbeddingModel --> dense_vector ANN 搜索 --|
  |-- BM25 Function --> sparse_vector BM25 搜索 --|--> Milvus hybridSearch + RRFRanker --> 最终分块排名
```

`RetrievalService` 是唯一的 RAG 查询入口：构造稠密向量查询与稀疏文本查询，调用 `hybridSearch`，再将 Milvus 命中映射为 `RetrievalResult`。RRF 由 Milvus 执行，服务端不再自行重新计算或混合不兼容的原始分数。

## 数据与兼容性

新 collection schema 使用 Milvus 2.5.4 需要的 VARCHAR 主键、文本字段、FLOAT_VECTOR 稠密向量、SPARSE_FLOAT_VECTOR 稀疏向量、BM25 Function 和相应索引。现有 LangChain4j `EmbeddingStore` 的 collection schema 不包含这些字段，因此使用新的 collection 名称，避免静默破坏已有向量；知识文档启用或重新同步时重建至新 collection。

`RetrievalResult` 新增 nullable 的 `semanticScore`、`bm25Score`、`rrfScore`。为 API 向后兼容，原 `similarity` 字段继续代表最终 RRF 分数。

## 错误处理

Hybrid Search 可用时返回融合结果。若 Milvus BM25 查询不可用，记录明确错误并降级为语义检索，保证聊天流程可继续；配置可显式关闭降级以便在生产环境尽早暴露配置错误。

## 测试

抽取 Milvus 客户端边界，使单元测试可验证两路 SearchRequest、RRF 参数与排序映射，无需真实 Milvus。补充结果融合、禁用文档过滤、语义降级和原 API 返回字段测试；真实 Milvus 集成测试只在 2.5.4 环境执行。
