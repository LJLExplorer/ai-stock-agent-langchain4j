# Stock Insight Agent

基于 Java 21、Spring Boot 和 LangChain4j 的股票研究与预测 Agent。

## 能力

- 实时行情：价格、涨跌幅、成交量、换手率
- 技术分析：MA、MACD、RSI、KDJ、布林带
- 基本面分析：营收、净利润、ROE、PE、PB、现金流
- 新闻/公告/财报 RAG 检索
- 支持 Planner 返回 JSON、Markdown 或带说明文字的非结构化结果
- 通过 `daily_stock_analysis` 分析流水线生成股票趋势预测
- 多股票比较、投资组合分析
- Redis 短期会话记忆、字符窗口和滚动摘要
- 用户主动录入、向量化和语义召回的长期记忆
- MongoDB 业务会话历史与 Milvus 知识库

## Agent Tools

股票分析工作流通过受控任务节点调用以下 Tool。括号中的英文名称是内部函数名，界面返回中文名称：

- `查询实时行情`（`getRealtimeQuote`）：查询股票实时价格、涨跌幅、成交量和换手率
- `分析技术指标`（`analyzeTechnicalIndicators`）：计算 MA、MACD、RSI、KDJ、布林带等技术指标
- `分析财务报告`（`analyzeFinancialReport`）：分析营收、净利润、ROE、PE、PB 和现金流
- `搜索新闻与公告`（`searchStockNewsAndAnnouncements`）：检索股票新闻、公告、财报和行业报告，并返回来源与摘要
- `预测股票趋势`（`predictStockTrend`）：调用 `daily_stock_analysis` 分析流水线生成趋势预测
- `比较多只股票`（`compareStocks`）：对比多只股票的行情、技术面、基本面和预测结果
- `分析投资组合`（`analyzePortfolio`）：分析持仓收益、行业分布、集中度、风险和预测趋势

## 技术亮点

- `plan-execute-langgraph`：基于 LangGraph4j `StateGraph` 构建显式状态工作流：INIT、任务执行、Reflector、Critic、重试/补新闻、ANSWER 和失败终态均为真实图节点，不再由图外循环编排。
- Agent Planner + PlanValidator：无工具 Planner 先输出 `intent/symbol/tasks` 结构化计划；Validator 严格校验股票代码、任务枚举和工具白名单，非法任务无法进入执行器。
- Fan-out/Fan-in：行情、技术、财务和新闻分析被建模为独立任务节点，工作流支持并行分支汇合；每个任务都有独立状态、结果、错误信息和执行次数。
- MongoDB Checkpoint：`ExecutionState` 持久化到 `agent_execution_states`，记录 `executionId`、任务状态、当前节点、版本号、重试次数和结果；使用版本条件更新避免旧状态覆盖新状态。
- 幂等恢复：任务节点执行前检查任务状态，已完成任务直接复用结果；`WorkflowRunner.resume(executionId)` 可从 MongoDB 恢复未完成执行。
- Reflector + Critic：Reflector 复盘空结果、工具错误和标的一致性；Critic 以确定性规则裁决重试、补新闻、回答或失败。缺少新闻分析时动态追加白名单内的 `NEWS_ANALYSIS` 任务。
- 结构化安全边界：Planner 输出必须经过 JSON 解析、股票代码校验、任务枚举校验和 Tool 白名单校验后才能执行。
- 基于 Spring Boot 3.3、Java 21、LangChain4j 和 LangGraph4j 构建，使用 `AiServices` 负责回答生成和传统 Tool Calling，使用 StateGraph 负责可控工作流编排。
- 使用阿里云百炼 OpenAI-compatible 接口接入 `qwen3.7-plus`，embedding 使用 `qwen3.7-text-embedding`；配置中保留了可切回的 `qwen3.7-flash` 注释。
- 采用 Plan-and-Execute 工作流控制 Tool 调用顺序和任务边界；模型负责理解问题、生成计划和基于已校验结果生成回答。
- LangGraph4j `StateGraph` 负责节点编排，LangChain4j `AiServices` 负责 Planner、Answer Generator 和统一模型接入。
- 使用 Redis List 持久化 LangChain4j 短期记忆，支持多用户、多会话隔离；Redis Key 使用 `userId:sessionId`，保留工具调用消息链。
- 短期记忆按字符数控制，默认上限为 32,000 字符；超限后生成滚动摘要并保留最新窗口，摘要和消息窗口分开存储。
- 采用会话级访问控制、状态管理和并发隔离机制，支持多用户安全使用。
- 采用有界滚动摘要和最新上下文优先策略，提升长对话稳定性。
- MongoDB 继续保存会话元数据和用户可见的业务消息，Redis 负责 Agent 即时上下文。
- 支持通过前端长期记忆区域或 `/api/memories` 主动保存用户偏好；对话时按用户和语义相似度自动召回。
- 使用 Milvus 保存向量知识库，结合 embedding、相似度检索和上下文增强实现 RAG。
- 支持飞书文档同步，将外部知识文档接入统一知识库。
- 通过独立数据客户端封装行情、财务和新闻来源，便于替换数据供应商和扩展适配器。
- Planner 解析按 JSON → Markdown/自然语言的顺序降级，能从股票代码、标题和正文关键词中提取受限分析计划；解析失败时不会直接终止对话。
- LangGraph4j 图状态只携带可序列化的字符串上下文，完整 `ExecutionState` 由工作流执行层和 MongoDB Checkpoint 管理，避免状态序列化异常。
- 工具失败会记录到对应任务并进入有限重试；单个新闻接口失败不会直接升级为整个请求失败。
- 预测 Tool 通过 `PREDICTION_BASE_URL` 调用 `daily_stock_analysis` 的 `POST /api/v1/analysis/analyze` 接口，预测服务与 Agent 解耦。
- 使用 Spring Validation、全局异常处理和结构化 DTO，统一前后端接口响应。
- 工作流任务设置有限重试次数，并通过 `WorkflowRetryPolicy` 防止失败任务无限重试。
- 提供 RAG 轻量诊断记录，辅助区分“未召回、低质量召回、模型生成异常”和基础设施问题。

### Plan-and-Execute 执行链路

```text
用户问题
  ↓
AgentPlannerAssistant
  ↓
PlanValidator
  ↓
ExecutionState（MongoDB Checkpoint）
       ↓
LangGraph4j StateGraph
  ├─ MARKET_DATA
  ├─ TECHNICAL_ANALYSIS
  ├─ FINANCIAL_ANALYSIS
  └─ NEWS_ANALYSIS
       ↓
Reflector → 重试 / 动态追加任务 / 可信结果
       ↓
Critic → RETRY / ADD_NEWS / ANSWER / FAILED
       ↓
无工具 Answer Generator（仅消费 Critic 通过的结果）
```

当前工作流只覆盖股票分析场景。订单、物流和客服工单需要新增对应的任务枚举、业务规则和工具节点，不能直接套用股票分析任务。

工作流核心代码位于 `src/main/java/com/ljl/ai/agent/workflow/`：

- `StockAnalysisWorkflow`：定义 LangGraph4j 节点和边。
- `StockAnalysisTaskExecutor`：维护任务到业务 Tool 的唯一映射入口。
- `WorkflowReflector`：复盘任务结果、发现失败和新闻缺口。
- `WorkflowCritic`：将复盘结果裁决为重试、补新闻、回答或失败的图路由。
- `WorkflowAnswerGenerator`：使用无工具助手基于可信结果写入最终答案。
- `MongoExecutionStateStore`：实现 MongoDB Checkpoint 和乐观锁版本控制。
- `WorkflowRunner`：负责新建执行、调用 StateGraph 和 `executionId` 恢复；不再承担图外反思循环。

## 启动

环境要求：JDK 21、Maven、MongoDB、Redis。Milvus 用于知识库检索，未启动时不影响基础对话服务启动。

Redis 默认连接 `localhost:6379`，默认密码为 `test`，可通过 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD` 和 `REDIS_DATABASE` 覆盖。

```bash
# 当前 shell 已配置 jdk21 切换命令时使用
jdk21
mvn spring-boot:run
```

默认服务地址为 `http://localhost:8080`，健康检查接口为 `GET /api/health`。

### 新闻搜索配置

新闻搜索只需要配置一个可用 provider，不要求 Tavily 和 SerpAPI 同时配置。系统按以下顺序选择：

1. 配置 Tavily key 时优先使用 Tavily；
2. 未配置 Tavily、但配置 SerpAPI 时使用 SerpAPI；
3. 两者都未配置时，新闻任务返回结构化失败结果，其他分析任务仍可继续处理。

推荐通过环境变量配置，不要把真实 key 提交到仓库：

```bash
export TAVILY_API_KEY="<your-tavily-key>"
# 或仅配置 SerpAPI：
# export SERPAPI_API_KEY="<your-serpapi-key>"
```

Spring 配置也支持以下属性名：`news-search.tavily-api-key`、
`news-search.tavily-api-keys`、`news-search.serpapi-api-key` 和
`news-search.serpapi-api-keys`。修改配置后必须重启 Spring Boot，运行中的进程不会动态读取 YAML。

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

### RAG 诊断

启用 RAG 的每轮对话会在 MongoDB `rag_traces` 保存轻量诊断记录，用于判断问题来自召回还是模型生成。记录包含：

- `retrievalCount`：召回片段数量
- `topScore`：最高相似度
- `sourceIds` / `sourceTitles`：召回来源
- `contextLength` / `answerLength`：上下文和回答长度
- `success` / `errorMessage`：链路结果

不保存完整 Prompt、完整模型输出或完整增强上下文；诊断记录写入失败不会阻断对话。

### 记忆存储位置

| 数据 | 存储位置 | 用途 |
|------|----------|------|
| 当前窗口内的 LangChain4j 消息、Tool 请求和 Tool 结果 | Redis List `ai:memory:messages:{userId}:{sessionId}` | Agent 当前上下文与工具调用链 |
| 短期记忆摘要 | Redis String `ai:memory:summary:{userId}:{sessionId}` | 压缩较早对话并保持上下文连贯 |
| 用户长期记忆原文与元数据 | MongoDB `user_long_term_memories` | 记忆管理、用户隔离和向量关联 |
| 用户长期记忆向量 | Milvus | 语义相似度召回 |
| RAG 轻量诊断记录 | MongoDB `rag_traces` | 排查召回、模型和链路问题 |
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

### 关键配置

| 配置 | 默认值 | 作用 |
|------|--------|------|
| `memory.short-term.max-messages` | `20` | LangChain4j 当前消息窗口大小 |
| `memory.short-term.summary-trigger-messages` | `12` | 开始检查摘要的最小消息数 |
| `memory.short-term.max-chars` | `32000` | 短期记忆字符数上限 |
| `memory.short-term.summary-max-chars` | `8000` | 短期摘要最大字符数，超出时保留最新内容 |
| `memory.short-term.ttl` | `86400` | Redis 短期消息窗口 TTL，单位秒 |
| `memory.long-term.top-k` | `5` | 长期记忆最多召回数量 |
| `memory.long-term.min-score` | `0.72` | 长期记忆最小相似度 |
| `knowledge.retrieval.top-k` | `5` | RAG 默认召回片段数量 |
| `knowledge.retrieval.min-score` | `0.7` | RAG 最小相似度 |
| `agent.tool.max-sequential-invocations` | `10` | 单次对话连续 Tool 调用上限 |
| `news-search.tavily-api-key` | 空 | Tavily 单个 API key，配置后优先使用 |
| `news-search.serpapi-api-key` | 空 | SerpAPI 单个 API key，仅在 Tavily 未配置时使用 |

短期记忆相关配置支持环境变量覆盖：

```bash
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=test
SHORT_MEMORY_MAX_MESSAGES=20
SHORT_MEMORY_SUMMARY_TRIGGER=12
SHORT_MEMORY_MAX_CHARS=32000
SHORT_MEMORY_TTL=86400
LONG_MEMORY_TOP_K=5
LONG_MEMORY_MIN_SCORE=0.72
```

### RAG 诊断排查

RAG 成功完成并生成回答后，会在 MongoDB `rag_traces` 保存轻量记录。建议按以下顺序判断问题：

1. `retrievalCount = 0`：优先检查 Embedding、Milvus 连接、知识库内容和相似度阈值。
2. `retrievalCount > 0` 但 `topScore` 偏低：优先检查文档分块、Embedding 模型和检索阈值。
3. 来源和相似度正常但回答异常：优先检查系统提示词、模型生成和工具结果。
4. `success = false` 或存在 `errorMessage`：检查 RAG/模型调用链路异常。

诊断记录只保留查询、召回数量、最高分、来源 ID/标题、上下文长度、回答长度和错误信息，不保存完整 Prompt 或完整模型输出。

### Planner 与工作流故障排查

- 日志出现 `Planner 非 JSON`：表示模型返回了 Markdown 或解释文字，系统会尝试提取股票代码和分析任务；只要代码和任务可校验，仍会进入工作流。
- 日志出现 `NotSerializableException: ExecutionState`：应确认应用已重新编译并重启；当前工作流不会把完整执行状态放入 LangGraph 图状态。
- 日志出现 `任务无法开始: FAILED`：应确认运行的是最新构建版本。失败任务现在会进入有限重试，工具异常会转成任务失败结果，不会直接抛出为请求异常。
- 新闻任务提示未配置 key：检查启动进程实际加载的配置，并确认配置后已重启；只配置 Tavily 或只配置 SerpAPI 均可。

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

- `enableTools=true`：进入 LangGraph4j Plan-and-Execute 股票分析工作流，由 Planner 生成计划并按任务执行 Tool。
- `enableTools=false`：使用不注册 Tool 的普通对话 Agent。
- `enableRag=true`：先从 Milvus 检索知识，再将检索上下文注入 Agent。
- `enableRag=false`：跳过知识库检索。

前端位于 `frontend/`，启动方式：

```bash
cd frontend
npm install
npm run dev
```

前端默认访问 `http://localhost:5173`，后端接口代理到 `http://localhost:8080`。当前前端支持：

- 用户 ID、会话 ID 和股票代码设置
- 新建会话、加载历史会话、复制会话 ID
- 开关控制 RAG 和 Tool 调用
- 行情、技术分析、财务数据、新闻风险快捷提问
- 长期记忆主动录入，支持内容和逗号分隔标签
- 展示工具调用结果、知识来源、响应耗时和对话错误

## REST API

### 对话与会话

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/chat/send` | 发送对话消息，可控制 `enableRag`、`enableTools` |
| `GET` | `/api/chat/sessions/{sessionId}/messages?userId=...` | 获取会话历史消息并校验归属 |
| `GET` | `/api/chat/users/{userId}/sessions` | 获取用户会话列表 |
| `POST` | `/api/chat/sessions/{sessionId}/close?userId=...` | 关闭会话并校验归属 |
| `POST` | `/api/chat/messages/{messageId}/feedback` | 提交 `-1/0/1` 消息反馈 |

### 长期记忆

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/memories` | 主动新增用户长期记忆 |
| `GET` | `/api/memories?userId=...` | 查询用户已启用的长期记忆 |
| `GET` | `/api/memories/recall?userId=...&query=...` | 按语义相似度召回长期记忆 |
| `DELETE` | `/api/memories/{memoryId}?userId=...` | 删除用户自己的长期记忆及其向量 |

新增长期记忆示例：

```json
{
  "userId": "demo-user",
  "content": "我偏好关注新能源和半导体行业",
  "tags": ["投资偏好", "行业"]
}
```

### 知识库与 RAG

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/rag/search` | 执行指定 `topK` 的语义检索，范围 1-50 |
| `POST` | `/api/rag/query` | 执行 RAG 增强查询，失败时降级为普通查询 |
| `POST` | `/api/knowledge/feishu/sync` | 同步飞书文档 |
| `POST` | `/api/knowledge/documents` | 添加自定义知识文档，内容上限 10MB |
| `GET` | `/api/knowledge/documents` | 获取所有启用的知识文档 |
| `GET` | `/api/knowledge/documents/type/{type}` | 按文档类型查询 |
| `POST` | `/api/knowledge/documents/{documentId}/disable` | 禁用知识文档 |
| `DELETE` | `/api/knowledge/documents/{documentId}` | 删除知识文档及其向量 |

### 服务状态

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/health` | 健康检查 |
| `GET` | `/api/info` | 服务名称、版本和能力信息 |

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
