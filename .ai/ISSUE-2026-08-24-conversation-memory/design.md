# 会话记忆与 RAG 诊断设计

## 总体架构

```text
ChatService
  ├─ ShortTermMemoryService
  │   ├─ Redis List：短期消息窗口
  │   └─ Redis String：滚动摘要与摘要进度
  ├─ LongTermMemoryService
  │   ├─ MongoDB：用户记忆元数据
  │   └─ Milvus：用户记忆向量
  └─ RagTraceService
      └─ MongoDB：轻量 RAG 诊断记录
```

## 短期记忆设计

### Redis Key

```text
ai:memory:messages:{userId}:{sessionId}
ai:memory:summary:{userId}:{sessionId}
ai:memory:summary-index:{userId}:{sessionId}
```

消息 Key 使用 Redis List。每个元素保存一轮 LangChain4j 消息序列化结果，既支持用户/助手消息，也支持 AI 工具请求和工具执行结果。

### 摘要算法

1. 读取 Redis List 并计算消息文本字符数。
2. 未超过 32,000 字符时只维护滑动窗口，不生成摘要。
3. 超限时，将待压缩的历史内容与当前窗口前半部分交给摘要器。
4. 保留摘要和窗口后半部分。
5. 通过摘要进度 Key 记录已压缩位置，避免每次请求重复压缩相同内容。
6. 摘要失败时保留原窗口并记录日志，下一次继续尝试。

摘要器采用可注入接口，默认实现先提供稳定的摘要策略；后续可以接入独立模型而不改变 Redis 存储协议。

## 长期记忆设计

### MongoDB 实体

集合：`user_long_term_memories`

```text
memoryId, userId, content, tags, vectorId,
enabled, createTime, updateTime
```

### 向量元数据

Milvus TextSegment 元数据包含：

```text
memoryId
memoryType = USER_LONG_TERM
userId
```

检索采用 `topK * 5` 候选，再按 `userId`、`enabled` 和最小相似度过滤，最后返回配置数量的结果。

## RAG 诊断设计

### MongoDB 集合

集合：`rag_traces`

```text
traceId, userId, sessionId, messageId,
query, retrievalCount, topScore,
sourceIds, sourceTitles, contextLength,
answerLength, factCheckConfidence,
success, errorMessage, createTime
```

不保存完整模型输入输出和完整增强上下文。诊断记录采用尽力写入策略，写入失败只记录日志，不改变对话响应。

## 对话数据流

```text
请求
  → 获取 session
  → 读取短期摘要和 Redis 窗口
  → 召回用户长期记忆
  → 执行 RAG 并创建诊断草稿
  → 注入摘要/长期记忆/RAG 上下文
  → 调用 Agent
  → 更新 RAG 诊断结果
  → 更新 Redis 窗口和滚动摘要
  → 保存 MongoDB 业务消息
```

## 错误处理

- Redis 读取/写入失败：短期记忆组件返回明确异常，由对话层按现有错误处理策略处理。
- Embedding 或 Milvus 召回失败：记录告警并跳过长期记忆，不阻断主对话。
- 长期记忆删除时向量删除失败：保留 MongoDB 记录并返回失败，避免元数据与向量状态无法追踪。
- RAG 诊断写入失败：只记录日志，不能阻断模型回答。
- 所有长期记忆查询、召回和删除都必须校验 `userId`。

## 测试设计

- Redis Store 单元测试：List 序列化、消息顺序、删除、TTL、隔离。
- 摘要服务单元测试：阈值、窗口后半部分、摘要进度、防重复摘要、失败保留。
- 长期记忆服务单元测试：录入、用户隔离、topK、删除补偿。
- RAG Trace 单元测试：无结果、低分结果、正常结果、写入失败降级。
- ChatService 集成单元测试：上下文注入、长期记忆异常降级、RAG 诊断不阻断主流程。
