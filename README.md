# Stock Insight Agent

基于 Java 21、Spring Boot 和 LangChain4j 的股票研究与预测 Agent。包名为 `com.ljl.ai.agent`。

## 能力

- 实时行情：价格、涨跌幅、成交量、换手率
- 技术分析：MA、MACD、RSI、KDJ、布林带
- 基本面分析：营收、净利润、ROE、PE、PB、现金流
- 新闻/公告/财报 RAG 检索
- 通过 `daily_stock_analysis` 分析流水线生成股票趋势预测
- 多股票比较、自然语言选股、投资组合分析
- Redis 短期会话记忆、字符窗口和滚动摘要
- 用户主动录入、向量化和语义召回的长期记忆
- MongoDB 业务会话历史与 Milvus 知识库

## Agent Tools

智能体通过 LangChain4j 自动选择并调用以下 Tool。括号中的英文名称是内部函数名，界面返回中文名称：

- `查询实时行情`（`getRealtimeQuote`）：查询股票实时价格、涨跌幅、成交量和换手率
- `分析技术指标`（`analyzeTechnicalIndicators`）：计算 MA、MACD、RSI、KDJ、布林带等技术指标
- `分析财务报告`（`analyzeFinancialReport`）：分析营收、净利润、ROE、PE、PB 和现金流
- `搜索新闻与公告`（`searchStockNewsAndAnnouncements`）：检索股票新闻、公告、财报和行业报告，并返回来源与摘要
- `预测股票趋势`（`predictStockTrend`）：调用 `daily_stock_analysis` 分析流水线生成趋势预测
- `比较多只股票`（`compareStocks`）：对比多只股票的行情、技术面、基本面和预测结果
- `分析投资组合`（`analyzePortfolio`）：分析持仓收益、行业分布、集中度、风险和预测趋势
- `筛选股票`（`screenStocks`）：根据自然语言条件筛选股票

## 技术亮点

- 基于 Spring Boot 3.3、Java 21 和 LangChain4j 构建，使用 `AiServices` 编排对话智能体和 Tool 调用。
- 使用阿里云百炼 OpenAI-compatible 接口接入 `qwen3.7-flash`，embedding 使用 `qwen3.7-text-embedding`。
- 支持 ReAct 风格的多 Tool 调用，模型可根据问题自动选择工具、读取工具结果并继续调用其他工具，最后生成回答。
- ReAct 编排由 LangChain4j `AiServices`、ChatMemory 和模型原生 Tool Calling 协议共同完成；当前没有单独实现 `Thought/Action/Observation` 文本状态机。
- 使用 Redis List 持久化 LangChain4j 短期记忆，支持多用户、多会话隔离；Redis Key 使用 `userId:sessionId`，保留工具调用消息链。
- 短期记忆按字符数控制，默认上限为 32,000 字符；超限后生成滚动摘要并保留最新窗口，摘要和消息窗口分开存储。
- MongoDB 继续保存会话元数据和用户可见的业务消息，Redis 负责 Agent 即时上下文。
- 支持通过前端长期记忆区域或 `/api/memories` 主动保存用户偏好；对话时按用户和语义相似度自动召回。
- 使用 Milvus 保存向量知识库，结合 embedding、相似度检索和上下文增强实现 RAG。
- 支持飞书文档同步，将外部知识文档接入统一知识库。
- 通过独立数据客户端封装行情、财务和新闻来源，便于替换数据供应商和扩展适配器。
- 预测 Tool 通过 `PREDICTION_BASE_URL` 调用 `daily_stock_analysis` 的 `POST /api/v1/analysis/analyze` 接口，预测服务与 Agent 解耦。
- 使用 Spring Validation、全局异常处理和结构化 DTO，统一前后端接口响应。
- 使用 Logback 将日志写入 `Logwork/`，按天滚动并限制文件大小、保留周期和总容量。

## 启动

环境要求：JDK 21、Maven、MongoDB、Redis。Milvus 用于知识库检索，未启动时不影响基础对话服务启动。

Redis 默认连接 `localhost:6379`，可通过 `REDIS_HOST`、`REDIS_PORT` 和 `REDIS_DATABASE` 覆盖。

```bash
# 当前 shell 已配置 jdk21 切换命令时使用
jdk21
mvn spring-boot:run
```

默认服务地址为 `http://localhost:8080`，健康检查接口为 `GET /api/health`。

对话接口：`POST /api/chat/send`。示例请求：

```json
{
  "userId": "demo-user",
  "sessionId": "demo-session",
  "message": "分析贵州茅台最近的走势",
  "orderId": "600519.SH",
  "enableRag": false
}
```

请求中的 `orderId` 字段兼容原模板，当前作为股票代码使用；建议后续将其重命名为 `symbol`。

## 对话记忆与注意力

### 短期记忆

- 使用 LangChain4j `MessageWindowChatMemory` 实现会话上下文窗口。
- 使用 Redis List 保存短期消息，Key 格式为 `ai:memory:messages:{userId}:{sessionId}`。
- 每个会话默认最多保留最近 20 条 LangChain4j 消息作为模型当前请求的上下文。
- 消息包含用户消息、AI 回复、工具调用请求和工具执行结果，因此模型可以在同一轮中继续基于 Tool 结果推理。
- 当短期消息累计超过默认 32,000 字符时，`ShortTermSummaryService` 将较早内容压缩为摘要，并将摘要保存到 `ai:memory:summary:{userId}:{sessionId}`。
- Redis 短期记忆默认 TTL 为 86,400 秒，可通过 `SHORT_MEMORY_MAX_MESSAGES`、`SHORT_MEMORY_MAX_CHARS`、`SHORT_MEMORY_SUMMARY_TRIGGER` 和 `SHORT_MEMORY_TTL` 调整。

### 长期记忆

- 业务层会话保存到 `chat_sessions`，用户可见的对话消息保存到 `chat_messages`。
- 用户主动录入的长期记忆原文和元数据保存到 MongoDB `user_long_term_memories`，向量保存到 Milvus。
- 长期记忆向量包含 `userId` 和 `memoryId` 元数据，召回时先按相似度检索，再进行用户隔离和启用状态过滤。
- 每轮对话会将召回的长期记忆和短期摘要注入当前问题；长期记忆服务异常时自动降级，不阻断普通聊天。

长期记忆接口：

```text
POST   /api/memories
GET    /api/memories?userId=demo-user
GET    /api/memories/recall?userId=demo-user&query=我的投资偏好
DELETE /api/memories/{memoryId}?userId=demo-user
```

### 记忆存储位置

| 数据 | 存储位置 | 用途 |
|------|----------|------|
| 当前窗口内的 LangChain4j 消息、Tool 请求和 Tool 结果 | Redis List `ai:memory:messages:{userId}:{sessionId}` | Agent 当前上下文与工具调用链 |
| 短期记忆摘要 | Redis String `ai:memory:summary:{userId}:{sessionId}` | 压缩较早对话并保持上下文连贯 |
| 用户长期记忆原文与元数据 | MongoDB `user_long_term_memories` | 记忆管理、用户隔离和向量关联 |
| 用户长期记忆向量 | Milvus | 语义相似度召回 |
| 用户可见的对话消息 | MongoDB `chat_messages` | 前端历史消息展示 |
| 会话元数据、标题、标签、摘要 | MongoDB `chat_sessions` | 会话管理 |
| RAG 文档元数据 | MongoDB `knowledge_documents` | 文档管理与来源信息 |
| RAG 向量和文本片段 | Milvus | 相似度检索和知识增强 |

### MongoDB、Milvus、Redis 和模型的边界

- **MongoDB**：保存可持久化的业务会话数据、用户可见消息和知识文档元数据。默认连接 `mongodb://localhost:27017/customer_memory`。
- **Redis**：保存带 TTL 的短期消息窗口和滚动摘要，作为 Agent 的即时上下文缓存。
- **长期记忆**：MongoDB 保存可管理的原文和元数据，Milvus 保存向量；长期记忆通过 `userId` 做数据隔离。
- **Milvus**：只保存知识库文档的 embedding 和文本片段，用于 RAG 相似度检索；它不是对话记忆库，也不保存模型参数。
- **模型本身**：模型服务不保存本项目的会话记忆。每次请求由 Java 从 Redis 读取短期消息窗口和摘要，组装为请求上下文后发送给模型；模型只在本次请求中使用这些上下文。
- **模型参数**：模型参数属于外部模型服务，由模型服务提供方管理，不存储在 MongoDB、Milvus 或 Redis 中。

## Agent 请求开关

`POST /api/chat/send` 支持按请求控制能力：

```json
{
  "userId": "demo-user",
  "message": "分析 600519 的技术面和近期风险",
  "orderId": "600519.SH",
  "enableTools": true,
  "enableRag": true
}
```

- `enableTools=true`：使用带有行情、技术、财务、新闻、预测、比较、组合和选股 Tool 的 Agent。
- `enableTools=false`：使用不注册 Tool 的普通对话 Agent。
- `enableRag=true`：先从 Milvus 检索知识，再将检索上下文注入 Agent。
- `enableRag=false`：跳过知识库检索。

前端位于 `frontend/`，启动方式：

```bash
cd frontend
npm install
npm run dev
```

前端默认访问 `http://localhost:5173`，后端接口代理到 `http://localhost:8080`。

## 日志

日志写入当前项目目录下的 `Logwork/`：

- 当前日志：`Logwork/application.log`
- 按天归档：`Logwork/application.yyyy-MM-dd.i.log`
- 单文件最大 50 MB，最多保留 30 天

预测服务配置：

```bash
PREDICTION_BASE_URL=http://localhost:8000
PREDICTION_TIMEOUT_SECONDS=180
```

Java Agent 会调用 `POST {PREDICTION_BASE_URL}/api/v1/analysis/analyze`，请求 `stock_code`、`report_type`、`async_mode` 和 `notify`，并读取 `report.summary.trend_prediction`。

行情、新闻数据源通过 `MARKET_DATA_BASE_URL` 和 `NEWS_SEARCH_BASE_URL` 预留，接入 AkShare、Tushare、TickFlow、YFinance 或搜索服务时应在对应 Tool 内增加适配器，并保留来源和时间字段。

所有预测仅用于研究分析，不构成投资建议。
