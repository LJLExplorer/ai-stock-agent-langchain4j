# ISSUE-2026-08-25-session-history-markdown 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 新建会话立即落库、支持多会话历史恢复，并将助手 Markdown 正确渲染。

**架构：** 后端新增创建会话 REST 接口，复用现有 Mongo 会话服务；前端通过创建、列表、历史三个接口管理当前会话和多个历史会话。助手消息使用 `react-markdown`/`remark-gfm` 渲染。

**技术栈：** Spring Boot、MongoTemplate、React、Vite、react-markdown、remark-gfm。

**相关文档：**
- 需求文档：`.ai/ISSUE-2026-08-25-session-history-markdown/requirements.md`
- 设计文档：`.ai/ISSUE-2026-08-25-session-history-markdown/design.md`

**相关规范：**
- 当前仓库未提供 `yx-coder/` 规范目录；遵循现有 Java/React 代码风格。

**涉及组件：**
- MongoDB 会话持久化：`ChatMemoryService`

### Task 1: 新增后端创建会话接口

**状态：** completed

**Red Evidence：** 未单独执行 controller RED 测试；实现前确认现有接口不存在创建会话路由。

**Green Evidence：** `mvn -q -DskipTests compile` 通过；`mvn -q -Dtest=ChatServiceCreateSessionTest test` 通过。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/agent/controller/ChatController.java`
- Modify: `src/main/java/com/ljl/ai/agent/service/ChatService.java`
- Test: `src/test/java/com/ljl/ai/agent/controller/ChatControllerTest.java`

**步骤：** 增加 `POST /api/chat/sessions`，接收 userId/orderId，委托已有创建逻辑并返回会话；使用纯 Java 替身验证 userId 清理、空值校验和真实会话 ID 返回。

### Task 2: 前端会话创建与历史列表

**状态：** completed

**Red Evidence：** 前端按用户要求不增加 TDD 测试；实现前确认新建按钮仅清空本地状态且未调用会话列表/创建接口。

**Green Evidence：** `npm run build` 通过；实现包含创建会话、历史列表加载、筛选、点击恢复、用户隔离和发送后刷新。

**涉及文件：**
- Modify: `frontend/src/App.jsx`
- Modify: `frontend/src/styles.css`

**步骤：** 增加会话列表加载、筛选、点击恢复、新建立即创建、userId 切换隔离和发送后刷新；为多会话列表增加滚动布局和空态；运行前端构建验证，不增加前端 TDD 测试。

### Task 3: Markdown 消息渲染

**状态：** completed

**Red Evidence：** 前端按用户要求不增加 TDD 测试；实现前确认助手消息由普通字符串直接输出。

**Green Evidence：** `npm run build` 通过；`ReactMarkdown` 使用 `remarkGfm` 渲染助手消息，用户消息和错误消息仍为纯文本。

**涉及文件：**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Modify: `frontend/src/App.jsx`
- Modify: `frontend/src/styles.css`

**步骤：** 添加 Markdown 依赖，助手消息接入 `ReactMarkdown`/`remarkGfm`，补充元素样式；运行 `npm run build` 验证，不增加前端 TDD 测试。

### Task 4: 回归验证与交付

**状态：** completed

**Red Evidence：** 待填写

**Green Evidence：** `mvn -q -DskipTests compile`、`mvn -q -Dtest=ChatServiceCreateSessionTest test`、`npm run build` 和 `git diff --check` 均通过。

**涉及文件：**
- Modify: `.ai/ISSUE-2026-08-25-session-history-markdown/tasks.md`

**步骤：** 已运行后端编译/定向测试、前端构建和 diff 检查；完整后端测试未运行，因为项目现有基础设施测试依赖 MongoDB/Milvus/外部模型服务。
