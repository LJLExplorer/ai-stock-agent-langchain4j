# 配置与本地基础设施

项目将可公开的配置结构与个人凭据分开管理：`application.example.yml` 和 `.env.example` 可以提交；`application.yml`、`application-test.yml` 与 `.env` 只保留在本地。请勿把真实密钥写入示例文件、Compose 文件、测试或提交历史。

## 启动层级

| 能力层级 | 必需组件 | 必需配置 | 适合场景 |
| --- | --- | --- | --- |
| 编译与单元测试 | JDK 21、Maven 3.9+ | 无外部密钥、无数据库 | CI、代码评审、快速验证 |
| 基础对话 | MongoDB、Redis、一个兼容 OpenAI Chat API 的模型 | `OPENAI_API_KEY` | 会话、短期记忆、工作流状态 |
| 完整 RAG Agent | 基础对话组件、Milvus、DashScope Embedding | 再提供 `DASHSCOPE_API_KEY` | 知识库、混合检索、长期记忆 |
| 新闻与文档同步 | 完整 RAG Agent、第三方服务 | Tavily/SerpAPI 至少一个；飞书按需配置 | 联网新闻、飞书文档入库 |
| 预测工具 | 独立预测服务 | `PREDICTION_BASE_URL` | 调用可选的行情预测能力 |

## 首次配置

克隆后的首次配置可以从模板开始；如果本地文件已经存在，不要执行复制命令覆盖它：

```bash
cp src/main/resources/application.example.yml src/main/resources/application.yml
cp .env.example .env
```

在 `.env` 中填入个人密钥后，将变量导入当前终端，再启动应用。Spring Boot 也支持通过 IDE Run Configuration 或系统环境变量注入同名配置。

```bash
set -a
source .env
set +a
docker compose up -d
mvn spring-boot:run
```

前端单独启动：

```bash
cd frontend
npm ci
npm run dev
```

默认地址：后端 `http://localhost:8080`，前端 `http://localhost:5173`，Milvus `localhost:19530`，Milvus WebUI `http://localhost:9091/webui/`。

## Compose 服务

`compose.yaml` 固定了 MongoDB、Redis 和 Milvus 2.5 的镜像版本，并使用命名卷保存数据。Milvus Standalone 依赖的 etcd 与 MinIO 沿用 Milvus 2.5 官方 Compose 拓扑。默认 MinIO 账号只用于本机开发网络，不能用于生产环境。

版本选择以 [Milvus 2.5 Standalone 官方安装文档](https://milvus.io/docs/v2.5.x/install_standalone-docker-compose.md)、[Mongo Docker Official Image](https://hub.docker.com/_/mongo) 与 [Redis Docker Official Image](https://hub.docker.com/_/redis) 为依据；升级镜像时应先检查数据兼容性，再重新执行 `docker compose config --quiet` 和集成测试。

```bash
# 查看健康状态
docker compose ps

# 停止服务，保留数据
docker compose down

# 明确需要清空本地数据库时才删除命名卷
docker compose down --volumes
```

可以通过 `.env` 覆盖宿主机端口：`MONGODB_PORT`、`REDIS_PORT`、`MILVUS_PORT`、`MILVUS_WEBUI_PORT`。如需修改 Milvus 内部对象存储凭据，可设置 `MILVUS_MINIO_ACCESS_KEY` 与 `MILVUS_MINIO_SECRET_KEY`。

## 模型与第三方服务

- `OPENAI_API_KEY` 是 Chat Model 的凭据；`OPENAI_BASE_URL` 可指向兼容 OpenAI 协议的供应商。
- `DASHSCOPE_API_KEY` 用于向量化；`MILVUS_DIMENSION` 必须与 Embedding 模型输出维度一致。
- `TAVILY_API_KEY` 与 `SERPAPI_API_KEY` 用于新闻搜索，至少配置当前启用 Provider 对应的一项。
- `AGENT_NEWS_RECOVERY_ENABLED` 默认为 `false`。开启后复用 `OPENAI_API_KEY` 对应的 Chat Model，依据字段级校验原因在“调整关键词、缩短窗口、只查官方公告、资料不足”四种受限动作中选择；模型输出仍由服务端校验，不能放宽新闻协议或直接调用工具。
- `FEISHU_APP_ID` 与 `FEISHU_APP_SECRET` 只在飞书同步功能启用时需要。
- `TRACE_LOGGING_INCLUDE_CONTENT` 默认为 `false`。仅在受控环境短时排障时开启，并设置有限的 `TRACE_LOGGING_MAX_CONTENT_LENGTH`；日志可能包含用户输入、检索上下文和模型响应。

## 测试

```bash
# 默认测试不连接外部基础设施
mvn test

# 解析集成测试 Profile，但跳过真实连接用例
mvn -Pintegration-test -DskipITs=true verify

# 基础设施就绪后显式运行集成测试
mvn -Pintegration-test verify
```

配置模板只描述字段，不承载凭据。若密钥曾进入公开提交、构建日志或聊天记录，应在对应供应商控制台撤销并重新签发。

## 长期记忆演进开关

`MEMORY_LONG_TERM_GENERATE_ENABLED` 控制是否从已成功保存的用户原文异步提取明确且跨会话适用的偏好；默认 `false`。首版只接受带有“以后”“今后”“请记住”等长期标记的风险优先、回答简洁和分析周期偏好，临时要求、问题和助手推断不会生成记忆。

填入既有的 `OPENAI_API_KEY`（也可配合 `OPENAI_BASE_URL`、`OPENAI_MODEL_NAME` 使用兼容服务）后，可把 `MEMORY_LONG_TERM_MODEL_EXTRACTION_ENABLED=true` 打开结构化模型候选提取；`MEMORY_LONG_TERM_MODEL_MAX_INPUT_CHARS` 限制每条输入。模型输出仍须通过服务端的类型、证据原文和归属校验，失败或无效 JSON 会安全跳过，不发布记忆。

`MEMORY_LONG_TERM_READ_ENABLED` 控制是否将少量当前有效核心偏好固定加入回答上下文；默认 `false`。相关历史记忆仍走原有检索路径。先启用生成并检查 `user_memory_candidates`，确认候选质量后再开启读取。

`MEMORY_LONG_TERM_READ_USER_ALLOWLIST` 可填逗号分隔的用户 ID，仅向这些用户开启读取，用于灰度；留空则在读取总开关开启后对所有用户生效。

`MEMORY_LONG_TERM_CORE_PREFERENCE_LIMIT` 和 `MEMORY_LONG_TERM_CONTEXT_MAX_CHARS` 分别限制固定偏好数和记忆上下文预算。关闭两个开关可立即回退到旧行为，已生成记录保留供审计或删除。

生成开启后，用户消息先进入 `memory_processing_jobs`，由带租约的后台 worker 处理；`MEMORY_LONG_TERM_JOB_BATCH_SIZE`、`MEMORY_LONG_TERM_JOB_LEASE_SECONDS` 与 `MEMORY_LONG_TERM_JOB_MAX_ATTEMPTS` 控制吞吐、接管时限与死信阈值。`MEMORY_LONG_TERM_BACKFILL_BATCH_SIZE` 控制消息扫描游标每轮补回的历史消息数。相关长期记忆先在 MongoDB 限定用户、状态和有效期，再在 `MEMORY_LONG_TERM_MAX_USER_RECALL_CANDIDATES` 个用户范围内的候选中排序，不依赖共享向量库的全局 Top-K。

若业务记录已经标记删除或替代而向量删除暂时失败，系统会把不含正文的任务写入 `memory_vector_cleanup_tasks`，并按 `MEMORY_LONG_TERM_VECTOR_CLEANUP_POLL_DELAY_MS` 重试。该补偿不影响读取：MongoDB 状态会先阻止失效记忆进入上下文。

`MEMORY_LONG_TERM_VECTOR_REBUILD_POLL_DELAY_MS` 控制有效记录缺失向量标识时的补建轮询；只处理 MongoDB 中仍为有效的记录。
