# 设计方案：RAG 诊断日志化

## 方案选择

采用保留 `RagTrace` 诊断数据结构、替换持久化实现的方案。`RagTraceService` 继续作为统一入口，但移除 `MongoTemplate` 依赖，在 `saveBestEffort` 中补齐诊断元数据并输出结构化日志。

相比删除诊断结构，该方案能集中维护诊断字段；相比 Mongo 与日志双写，该方案减少无必要的外部依赖和失败链路。

## 架构与数据流

`ChatService` 及其他现有调用方继续构造 `RagTrace` 并调用 `RagTraceService.saveBestEffort`。服务为缺失的 traceId/创建时间补值，然后以单条 INFO 日志输出关键字段；日志输出异常被隔离，不向上抛出。MongoDB 不再参与 RAG 诊断数据流。

## 日志内容

日志使用固定事件名和字段，覆盖 traceId、userId、sessionId、query、retrievalCount、topScore、sourceIds、sourceTitles、contextLength、createTime，便于人工按 traceId 或会话标识检索。

## 错误处理

诊断记录属于 best-effort 行为。日志记录发生异常时仅输出告警，不影响 RAG 查询、回答生成或对话主流程。

## 测试

测试直接构造服务并验证无需 Mongo 依赖即可记录诊断；同时验证日志实现异常时不会向调用方抛出异常。现有 RAG 相关测试与项目编译测试一并执行。
