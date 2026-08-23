# Stock Insight Agent

基于 Java 21、Spring Boot 和 LangChain4j 的股票研究与预测 Agent。包名为 `com.ljl.ai.agent`。

## 能力

- 实时行情：价格、涨跌幅、成交量、换手率
- 技术分析：MA、MACD、RSI、KDJ、布林带
- 基本面分析：营收、净利润、ROE、PE、PB、现金流
- 新闻/公告/财报 RAG 检索
- LSTM、Transformer、PatchTST 等外部时序模型预测
- 多股票比较、自然语言选股、投资组合分析
- MongoDB 会话记忆与 Milvus 知识库

## Agent Tools

智能体通过 LangChain4j 自动选择并调用以下 Tool：

- `getRealtimeQuote`：查询股票实时价格、涨跌幅、成交量和换手率
- `analyzeTechnicalIndicators`：计算 MA、MACD、RSI、KDJ、布林带等技术指标
- `analyzeFinancialReport`：分析营收、净利润、ROE、PE、PB 和现金流
- `searchStockNewsAndAnnouncements`：检索股票新闻、公告、财报和行业报告，并返回来源与摘要
- `predictStockTrend`：调用 LSTM、Transformer、PatchTST 等外部时序模型预测趋势
- `compareStocks`：对比多只股票的行情、技术面、基本面和预测结果
- `analyzePortfolio`：分析持仓收益、行业分布、集中度、风险和预测趋势
- `screenStocks`：根据自然语言条件筛选股票

## 技术亮点

- 基于 Spring Boot 3.3、Java 21 和 LangChain4j 构建，使用 `AiServices` 编排对话智能体和 Tool 调用。
- 使用阿里云百炼 OpenAI-compatible 接口接入 `qwen3.7-flash`，embedding 使用 `qwen3.7-text-embedding`。
- 支持 ReAct 风格的多 Tool 调用，模型可根据问题自动组合行情、技术面、基本面、新闻和预测能力。
- 通过 MongoDB 持久化会话、消息和 LangChain4j ChatMemory，支持多用户、多会话连续对话。
- 使用 Milvus 保存向量知识库，结合 embedding、相似度检索和上下文增强实现 RAG。
- 支持飞书文档同步，将外部知识文档接入统一知识库。
- 通过独立数据客户端封装行情、财务和新闻来源，便于替换数据供应商和扩展适配器。
- 支持外部 LSTM、Transformer、PatchTST 预测服务，预测服务与 Agent 解耦。
- 使用 Spring Validation、全局异常处理和结构化 DTO，统一前后端接口响应。
- 使用 Logback 将日志写入 `Logwork/`，按天滚动并限制文件大小、保留周期和总容量。

## 启动

环境要求：JDK 21、Maven、MongoDB。Milvus 用于知识库检索，未启动时不影响基础对话服务启动。

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

预测服务需提供 `POST {PREDICTION_BASE_URL}/predict`，请求字段为 `symbol`、`horizon`、`model`，返回 JSON 或文本均可。

行情、新闻数据源通过 `MARKET_DATA_BASE_URL` 和 `NEWS_SEARCH_BASE_URL` 预留，接入 AkShare、Tushare、TickFlow、YFinance 或搜索服务时应在对应 Tool 内增加适配器，并保留来源和时间字段。

所有预测仅用于研究分析，不构成投资建议。
