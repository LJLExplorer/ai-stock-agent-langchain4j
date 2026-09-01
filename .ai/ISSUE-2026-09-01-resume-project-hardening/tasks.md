# ISSUE-2026-09-01-resume-project-hardening 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 将现有股票研究 Agent 整理为可复现、可验证、无敏感信息，且适合 Java 后端、AI Agent 与校招岗位展示的公开简历项目。

**架构：** 保留 LangChain4j Agent、LangGraph4j 工作流、Milvus 混合检索和分层记忆架构，通过仓库边界清理、配置模板、测试分层、持续集成和代码质量修复建立工程证据链。README 只呈现能够由源码、自动化测试或明确运行步骤证明的能力。

**技术栈：** Java 21、Spring Boot 3.3、LangChain4j、LangGraph4j、MongoDB、Redis、Milvus、Maven、React、Vite、GitHub Actions、Docker Compose

**相关文档：**
- 需求文档：`.ai/ISSUE-2026-09-01-resume-project-hardening/requirements.md`
- 设计文档：`.ai/ISSUE-2026-09-01-resume-project-hardening/design.md`

**相关规范：**
- 项目未提供 `./yx-coder/AGENT.md`、架构规范或编码规范；以现有代码风格和本 Issue 文档为准。

**涉及组件：**
- MongoDB：会话、长期记忆、知识元数据与工作流 Checkpoint
- Redis：短期消息窗口与递归摘要
- Milvus：知识库与长期记忆向量检索

## 全局执行约束

- 每个 Task 开始前先把状态改为 `in_progress`。
- 先执行 Red 命令并将实际结果写入 `Red Evidence`，再允许修改交付文件。
- Green 命令通过后写入 `Green Evidence`，把状态改为 `completed`，然后提交。
- 不在日志、任务证据或提交信息中粘贴任何凭据值。
- 不读取、提交或改写被 `.gitignore` 排除的个人配置；若任何凭据曾离开受信边界，应由仓库所有者在服务端轮换。

### Task 1: 提供脱敏配置模板并校验跟踪文件

**状态：** completed

**Red Evidence：**
- Command: `test -f src/main/resources/application.example.yml && test -f .env.example`
- Actual: exit 1，两个脱敏配置模板均不存在。
- Match Expected: yes

**Green Evidence：**
- Command: `test -f src/main/resources/application.example.yml && test -f .env.example && ! git grep -InP '(?i)^[ \t]*(?:api-key|app-secret|password):[ \t]*(?:[^\s$][^\s]*|\$\{[^}:]+:[^}\s]+\})' -- src/main/resources/application.example.yml && ! git grep -InP '^[A-Z][A-Z0-9_]*(?:KEY|SECRET|TOKEN|PASSWORD)=\S+' -- .env.example && ! git grep -InP '(?<![A-Za-z0-9])sk-[A-Za-z0-9._-]{20,}' -- ':!*.md'`
- Actual: exit 0，无输出；YAML 密钥字段无字面量或非空占位默认值，`.env.example` 凭据变量均为空，且所有受跟踪的非 Markdown 文件未命中 token 模式。
- Match Expected: yes
- Command correction: YAML 模式锚定行首配置键，避免把安全占位符变量名中的 `PASSWORD:` 误判为 YAML 键；全仓 token 模式使用 `(?<![A-Za-z0-9])` 排除 `task-based-asynchronous-programming` 的词内误报。

**涉及文件：**
- Create: `src/main/resources/application.example.yml`
- Create: `.env.example`

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`，不读取或修改被 `.gitignore` 排除的个人配置。

**步骤 1：记录失败证据**

Run: `test -f src/main/resources/application.example.yml && test -f .env.example`

Expected: FAIL，仓库没有可提交的脱敏配置模板。

**步骤 2：最小修复**

- 从本地配置键结构生成脱敏 `application.example.yml`，使用 `CHANGE_ME` 或空值表示必填项。
- 新增 `.env.example`，只列变量名和非敏感示例值。
- 不复制或修改本地被忽略的 `application.yml`、`application-test.yml`。

**步骤 3：验证通过**

Run: `test -f src/main/resources/application.example.yml && test -f .env.example && ! git grep -InP '(?i)^[ \t]*(?:api-key|app-secret|password):[ \t]*(?:[^\s$][^\s]*|\$\{[^}:]+:[^}\s]+\})' -- src/main/resources/application.example.yml && ! git grep -InP '^[A-Z][A-Z0-9_]*(?:KEY|SECRET|TOKEN|PASSWORD)=\S+' -- .env.example && ! git grep -InP '(?<![A-Za-z0-9])sk-[A-Za-z0-9._-]{20,}' -- ':!*.md'`

Expected: PASS。

**步骤 4：回写状态并提交**

Commit: `docs: 增加脱敏配置模板`

### Task 2: 修复 Maven 依赖与 JDK 21 测试运行时

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Modify: `pom.xml`
- Create: `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`。

**步骤 1：记录失败证据**

Run: `mvn -q -Dtest=AgentConfigToolSelectionTest test`

Expected: FAIL，Mockito inline MockMaker 无法在 JDK 21 自附加；同时人工确认 `pom.xml` 重复声明 OkHttp。

**步骤 2：最小修复**

- 删除重复的 OkHttp 依赖，只保留一处版本声明。
- 删除无意义的 JUnit 4 直接依赖，统一使用 Spring Boot Test 的 JUnit Jupiter。
- 配置 Mockito 使用 subclass MockMaker，避免依赖受限环境中的 JVM 动态自附加。

**步骤 3：验证通过**

Run: `mvn -q -Dtest=AgentConfigToolSelectionTest,MarketDataToolTest test`

Expected: PASS，且 `mvn dependency:tree` 不再报告重复依赖声明。

**步骤 4：回写状态并提交**

Commit: `build: 修复 JDK 21 测试运行与重复依赖`

### Task 3: 隔离真实基础设施集成测试

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Rename: `src/test/java/com/ljl/ai/MilvusConnectionTest.java` → `src/test/java/com/ljl/ai/MilvusConnectionIT.java`
- Rename: `src/test/java/com/ljl/ai/SimpleMongoConnectionTest.java` → `src/test/java/com/ljl/ai/SimpleMongoConnectionIT.java`
- Modify: `pom.xml`

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`。

**步骤 1：记录失败证据**

Run: `mvn -q test`

Expected: FAIL，默认测试尝试连接 MongoDB/Milvus 或外部模型。

**步骤 2：最小修复**

- 将真实连接验证测试改为 `*IT` 命名并同步类名。
- 在 Maven `integration-test` Profile 中使用 Failsafe 执行 `**/*IT.java`。
- 集成测试失败时必须真正失败，不捕获异常后伪装通过；清理测试写入的数据。
- 将 `StockAnalysisAgentApplicationTests` 改为不启动全部外部基础设施的最小应用入口测试，删除无意义输出。

**步骤 3：验证通过**

Run: `mvn -q test`

Expected: PASS，且日志中没有连接 MongoDB/Milvus 的尝试。

Run: `mvn -q -Pintegration-test -DskipITs=true verify`

Expected: PASS，证明 Profile 配置可解析；真实集成环境执行方式留给完整环境验证。

**步骤 4：回写状态并提交**

Commit: `test: 分离单元测试与基础设施集成测试`

### Task 4: 清理公开仓库边界

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Modify: `.gitignore`
- Delete from Git: `.agents/`
- Delete from Git: `.claude/`
- Delete from Git: `.idea/`

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`。

**步骤 1：记录失败证据**

Run: `git ls-files .agents .claude .idea | wc -l`

Expected: FAIL，输出大于 0（基线为 353 个文件）。

**步骤 2：最小修复**

- 在 `.gitignore` 中加入 `.agents/`、`.claude/` 和 `.idea/`。
- 仅从 Git 索引/项目交付中移除这些本地资产；不扩大删除范围到用户其他目录。

**步骤 3：验证通过**

Run: `test -z "$(git ls-files .agents .claude .idea)"`

Expected: PASS。

**步骤 4：回写状态并提交**

Commit: `chore: 移除公开仓库中的本地工具资产`

### Task 5: 修正 memory 包命名

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Rename: `src/main/java/com/ljl/ai/memoery/` → `src/main/java/com/ljl/ai/memory/`
- Rename: `src/test/java/com/ljl/ai/memoery/` → `src/test/java/com/ljl/ai/memory/`
- Modify imports: `src/main/java/com/ljl/ai/agent/AgentConfig.java`, `src/main/java/com/ljl/ai/service/ChatService.java` 及所有编译器指出的引用

**说明：** 这是原子包迁移；拆分会让中间提交无法编译，因此目录内文件作为一个行为单元处理。

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`。

**步骤 1：记录失败证据**

Run: `rg -n 'memoery' src/main src/test`

Expected: FAIL，命中包声明、导入和目录。

**步骤 2：最小修复**

- 使用 Git 感知的重命名迁移生产代码和测试目录。
- 将包声明与全部 import 从 `com.ljl.ai.memoery` 改为 `com.ljl.ai.memory`。
- 不改变记忆业务行为、Redis Key 或 MongoDB collection。

**步骤 3：验证通过**

Run: `! rg -n 'memoery' src/main src/test && mvn -q test`

Expected: PASS。

**步骤 4：回写状态并提交**

Commit: `refactor: 修正 memory 包命名`

### Task 6: 清理后端代码观感问题

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/controller/ChatController.java`
- Modify: `src/main/java/com/ljl/ai/controller/KnowledgeController.java`
- Modify: `src/main/java/com/ljl/ai/controller/RagController.java`
- Test: 对应 Controller 测试（最多按控制器拆成后续小提交）

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`；若单次超过 3 个生产文件，按控制器顺序拆成 6A/6B/6C 提交。

**步骤 1：记录失败证据**

Run: `rg -n '@Autowired|BUG B[0-9]+' src/main/java/com/ljl/ai/controller`

Expected: FAIL，命中字段注入和历史修复编号。

**步骤 2：最小修复**

- 使用 `@RequiredArgsConstructor` 与 `final` 字段进行构造器注入。
- 删除“BUG B00x 修复”类历史注释，保留解释业务原因的注释。
- 保持接口路径、状态码和响应结构不变；已有参数校验测试必须继续通过。

**步骤 3：验证通过**

Run: `! rg -n '@Autowired|BUG B[0-9]+' src/main/java/com/ljl/ai/controller && mvn -q -Dtest='*ControllerTest' test`

Expected: PASS。

**步骤 4：回写状态并提交**

Commit: `refactor: 统一控制器依赖注入与注释`

### Task 7: 收紧诊断日志的隐私默认值

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/observability/TraceLoggingConfig.java`
- Modify: `src/main/java/com/ljl/ai/observability/TracingChatLanguageModel.java`
- Test: `src/test/java/com/ljl/ai/observability/TracingChatLanguageModelTest.java`

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`。

**步骤 1：编写失败测试**

- 增加测试：默认配置下模型请求/响应只记录元数据和受限摘要；显式开启完整内容后才允许记录内容，并继续应用长度上限。

Run: `mvn -q -Dtest=TracingChatLanguageModelTest test`

Expected: FAIL，新隐私默认行为尚未实现。

**步骤 2：最小修复**

- 增加显式 `include-content` 配置，默认 `false`。
- 默认日志保留 traceId、模型、耗时、消息数量、成功/失败等排障字段，不输出完整用户问题和检索上下文。
- 开启完整内容时仍使用最大长度限制。

**步骤 3：验证通过**

Run: `mvn -q -Dtest=TracingChatLanguageModelTest test`

Expected: PASS。

**步骤 4：回写状态并提交**

Commit: `security: 默认隐藏模型诊断日志内容`

### Task 8: 提供本地基础设施编排

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Create: `compose.yaml`
- Create: `docs/configuration.md`
- Modify: `.env.example`

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`。

**步骤 1：记录失败证据**

Run: `test -f compose.yaml && docker compose config --quiet`

Expected: FAIL，文件不存在。

**步骤 2：最小修复**

- 依据 MongoDB、Redis 和 Milvus 官方当前文档，使用固定版本镜像提供本地开发编排。
- 为有状态服务配置命名卷和健康检查；不在 Compose 中写入生产凭据。
- `docs/configuration.md` 给出最小能力、完整能力和可选第三方服务的配置矩阵。

**步骤 3：验证通过**

Run: `docker compose config --quiet`

Expected: PASS；若本机无 Docker，记录 `docker compose` 不可用，并至少用 YAML 解析器验证语法。

**步骤 4：回写状态并提交**

Commit: `build: 增加本地基础设施编排`

### Task 9: 建立后端与前端持续集成

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Create: `.github/workflows/ci.yml`
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`。

**步骤 1：记录失败证据**

Run: `test -f .github/workflows/ci.yml`

Expected: FAIL。

**步骤 2：最小修复**

- 依据 GitHub 官方 Actions 文档选择受支持的固定主版本。
- 后端 Job 使用 Java 21 和 Maven 缓存运行 `mvn -B test`。
- 前端 Job 使用锁文件执行 `npm ci` 和 `npm run build`。
- 增加前端 `engines` 约束，避免 `latest` 依赖造成不可解释漂移；本 Task 不做 UI 改版。

**步骤 3：验证通过**

Run: `mvn -q test && npm --prefix frontend ci && npm --prefix frontend run build`

Expected: PASS。

**步骤 4：回写状态并提交**

Commit: `ci: 增加后端测试与前端构建检查`

### Task 10: 重写面向面试官的 README

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Modify: `README.md`
- Create: `docs/resume-and-interview.md`
- Modify: `.ai/ISSUE-2026-09-01-resume-project-hardening/changelog.md`

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`。

**步骤 1：记录失败证据**

Run: `rg -n 'CI|测试分层|配置矩阵|设计取舍|已知边界|简历描述' README.md docs/resume-and-interview.md`

Expected: FAIL，现有文档缺少完整的工程证据链或目标文件不存在。

**步骤 2：最小修复**

- 按设计文档的信息架构重写 README，保留真实而有深度的架构说明。
- Mermaid 图必须与 `ChatService`、`StockAnalysisWorkflow`、RAG 和记忆实现一致。
- 快速开始引用实际的示例配置、Compose、端口和测试命令。
- `docs/resume-and-interview.md` 分别给出 Java 后端、AI Agent、校招通用三版简历描述，以及能够由代码支撑的追问与回答要点。
- 不写未经基准测试验证的 QPS、延迟、准确率、收益率或“生产级”表述。

**步骤 3：验证通过**

Run: `test -f docs/resume-and-interview.md && rg -n 'Plan-and-Execute|RRF|Checkpoint|mvn test|npm.*build|已知边界' README.md`

Expected: PASS，并人工逐项核对 README 中的类名、文件、端口、接口和命令。

**步骤 4：回写状态并提交**

Commit: `docs: 重写项目说明与简历面试材料`

### Task 11: 完成全量验收与安全收尾

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Modify: `.ai/ISSUE-2026-09-01-resume-project-hardening/tasks.md`
- Modify: `.ai/ISSUE-2026-09-01-resume-project-hardening/changelog.md`

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`。

**步骤 1：执行验收**

Run: `mvn -q test`

Expected: PASS。

Run: `npm --prefix frontend run build`

Expected: PASS。

Run: `git diff --check && test -z "$(git ls-files .agents .claude .idea)"`

Expected: PASS。

Run: `git grep -nE 'sk-[A-Za-z0-9._-]{20,}|(app-secret|password):[[:space:]]*[^$[:space:]]' -- ':!*.md'`

Expected: 无真实凭据命中；仅允许明确的测试占位值，并逐项人工确认。

**步骤 2：回写最终证据**

- 记录测试数量、前端构建结果、Compose 验证结果和未执行的外部集成测试。
- 更新 changelog，明确需要用户在外部平台完成凭据轮换。
- 不自动重写 Git 历史；若用户授权，另建独立操作方案并先创建可恢复备份。

**步骤 3：标记完成并提交**

Commit: `docs: 记录简历项目整改验收结果`
