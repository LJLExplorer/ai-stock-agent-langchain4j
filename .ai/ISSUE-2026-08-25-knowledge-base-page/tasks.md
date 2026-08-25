# ISSUE-2026-08-25-knowledge-base-page 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 增加基于 React Router 的知识库管理页，真实支持知识文档新增、查看、禁用和删除，并保持问答页可返回。

**架构：** 使用 `BrowserRouter` 管理 `/` 问答页和 `/knowledge` 知识库页；共享顶栏导航，知识库页通过现有 `/api/knowledge` 接口完成文档生命周期管理。后端补充“查询全部文档”和禁用时的向量清理，确保管理状态与 RAG 检索状态一致。

**技术栈：** Spring Boot、MongoTemplate、LangChain4j EmbeddingStore、React、Vite、react-router-dom、lucide-react、现有 CSS。

**相关文档：**
- 需求文档：`.ai/ISSUE-2026-08-25-knowledge-base-page/requirements.md`
- 设计文档：`.ai/ISSUE-2026-08-25-knowledge-base-page/design.md`

**相关规范：**
- 架构规范：`@./yx-coder/规范/架构规范.md`（当前仓库未提供，按现有 Spring 分层与前端结构执行）
- 编码规范：`@./yx-coder/规范/编码规范.md`（当前仓库未提供，按现有项目风格执行）
- UI 规范：`@/Users/ljl/code/Agent/ai-stock-agent-langchain4j/.agents/skills/ui-ux-pro-max/SKILL.md`

**涉及组件：**
- 无新增数据库组件；沿用 `MongoTemplate` 与现有 `EmbeddingStore`。

## 执行总规则

- 每个 Task 开始时先把状态改为 `in_progress`，状态更新完成前禁止改生产代码。
- 每个 Task 先写失败测试或最小可验证构建，再实现，再运行 GREEN 验证。
- 每个 Task 完成后回写 Red/Green Evidence，将状态改为 `completed`，再开始下一个 Task。
- 前端当前没有测试运行器，前端 Task 的 GREEN 证据使用 `npm run build`；如新增测试基础设施，须单独拆 Task。

### Task 1: 补齐知识文档查询与禁用的后端语义

**状态：** completed

**Red Evidence：**

- Command: `mvn -q -Dtest=KnowledgeControllerTest,KnowledgeServiceTest test`
- Actual: 编译失败，`KnowledgeService` 尚未提供 `findAll()`。
- Match Expected: yes

**Green Evidence：**

- Command: `mvn -q -Dtest=KnowledgeControllerTest,KnowledgeServiceTest test`
- Actual: PASS，知识库列表可包含已禁用文档，禁用流程先清理向量再保存禁用状态。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/agent/knowledge/KnowledgeService.java`
- Modify: `src/main/java/com/ljl/ai/agent/controller/KnowledgeController.java`
- Test: `src/test/java/com/ljl/ai/agent/controller/KnowledgeControllerTest.java`（如现有测试结构适合则复用或创建）

**步骤 1：更新状态**

- 将 Task 1 状态改为 `in_progress`。

**步骤 2：编写失败测试**

- 覆盖 `GET /api/knowledge/documents` 返回已启用和已禁用文档。
- 覆盖禁用文档时，服务层删除该文档全部向量后才写入 `enabled=false`。
- 覆盖向量删除失败时不保存禁用状态。

**步骤 3：运行 RED**

- Run: `mvn -q -Dtest=KnowledgeControllerTest test`
- Expected: FAIL，现有查询只返回启用文档，禁用流程未清理向量。
- 将实际摘要填写到 `Red Evidence`。

**步骤 4：最小实现**

- 增加查询全部未删除文档的方法，并按更新时间倒序返回。
- 控制器改用该查询方法。
- 禁用流程先调用现有带重试的向量删除逻辑；成功后设置 `enabled=false`、清空 `vectorIds` 和 `chunkCount`，失败则保持原状态。
- 对不存在的禁用/删除目标返回明确的 404 或失败响应，不改变现有新增接口契约。

**步骤 5：运行 GREEN 并回写**

- Run: `mvn -q -Dtest=KnowledgeControllerTest test`
- Expected: PASS。
- 填写 `Green Evidence`，将状态改为 `completed`。

**步骤 6：提交**

- `git add src/main/java/com/ljl/ai/agent/knowledge/KnowledgeService.java src/main/java/com/ljl/ai/agent/controller/KnowledgeController.java src/test/java/com/ljl/ai/agent/controller/KnowledgeControllerTest.java`
- `git commit -m "fix: 保证知识文档禁用后不参与RAG"`

### Task 2: 引入 React Router 并拆分页面入口

**状态：** completed

**Red Evidence：**

- Command: `rg -n "BrowserRouter|react-router-dom|/knowledge|KnowledgePage" frontend/src frontend/package.json`
- Actual: 无匹配结果，当前项目尚未接入路由或知识库路径。
- Match Expected: yes

**Green Evidence：**

- Command: `cd frontend && npm install && npm run build`
- Actual: PASS，已安装 `react-router-dom@7.18.2` 与 `react-router@7.18.2`，`/` 与 `/knowledge` 路由编译成功。

**涉及文件：**
- Modify: `frontend/package.json`
- Modify: `frontend/src/main.jsx`
- Create: `frontend/src/App.jsx`（保留现有问答行为并拆出路由结构）

**步骤 1：更新状态**

- 将 Task 2 状态改为 `in_progress`。

**步骤 2：写入失败验证**

- 安装依赖后先确认目标路由组件尚不存在，并运行 `npm run build` 记录当前基线或失败原因。

**步骤 3：最小实现**

- 增加 `react-router-dom` 依赖。
- 在 `main.jsx` 增加 `BrowserRouter`。
- 在 `App.jsx` 建立 `/` 与 `/knowledge` 路由；问答页抽为独立页面组件或保持可读的内部组件。
- 使用共享布局和 `NavLink`，保留现有问答页全部 API、会话和 RAG 开关行为。

**步骤 4：运行 GREEN**

- Run: `cd frontend && npm install && npm run build`
- Expected: PASS。
- 填写 `Green Evidence`，确认构建生成成功。

**步骤 5：提交**

- `git add frontend/package.json frontend/package-lock.json frontend/src/main.jsx frontend/src/App.jsx`
- `git commit -m "feat: 引入前端页面路由"`

### Task 3: 实现知识库页面的数据操作

**状态：** completed

**Red Evidence：**

- Command: `mvn -q test`
- Actual: 测试编译被工作区原有未跟踪文件 `src/test/java/com/ljl/ai/agent/workflow/LangGraph4jDependencyTest.java` 阻塞，缺少 `org.bsc.langgraph4j` 依赖；该文件不属于本 Issue。
- Match Expected: no（外部工作区阻塞）

**Green Evidence：**

- Command: `cd frontend && npm run build`
- Actual: PASS，知识库页面、表单、列表及新增/加载/禁用/删除请求均成功编译。

**涉及文件：**
- Create: `frontend/src/pages/KnowledgePage.jsx`
- Create: `frontend/src/components/knowledge/KnowledgeForm.jsx`
- Create: `frontend/src/components/knowledge/KnowledgeList.jsx`

**步骤 1：更新状态**

- 将 Task 3 状态改为 `in_progress`。

**步骤 2：写入失败验证**

- 先在路由中引用尚不存在的页面并运行 `npm run build`，记录模块缺失的 RED 证据。

**步骤 3：最小实现**

- 页面加载时调用 `GET /api/knowledge/documents`，维护列表、加载和错误状态。
- 表单提交调用 `POST /api/knowledge/documents`，发送标题、正文、类型、逗号分隔标签和空 metadata。
- 成功后清空表单并刷新列表；失败时保留输入内容并展示后端错误。
- 列表渲染标题、文档类型、标签、chunk 数、更新时间、启用状态。
- 禁用调用 `POST /api/knowledge/documents/{id}/disable`；删除调用 `DELETE /api/knowledge/documents/{id}`。
- 操作期间仅禁用对应按钮，避免重复请求；删除前使用确认提示。

**步骤 4：运行 GREEN**

- Run: `cd frontend && npm run build`
- Expected: PASS。
- 填写 `Green Evidence`，确认知识库页被正确打包。

**步骤 5：提交**

- `git add frontend/src/pages/KnowledgePage.jsx frontend/src/components/knowledge/KnowledgeForm.jsx frontend/src/components/knowledge/KnowledgeList.jsx`
- `git commit -m "feat: 添加知识库文档管理页面"`

### Task 4: 完成共享导航和知识库视觉交互

**状态：** completed

**Red Evidence：** 待填写

**Green Evidence：**

- Command: `cd frontend && npm run build`
- Actual: PASS，问答/知识库导航、知识库双栏布局、状态反馈、窄屏样式和可访问交互均成功编译。
- 手工检查：知识库页提供“问答”和“返回问答”入口；问答页提供“知识库”入口；按钮有加载/禁用状态，禁用和删除有确认提示。

**涉及文件：**
- Modify: `frontend/src/App.jsx`
- Modify: `frontend/src/pages/KnowledgePage.jsx`
- Modify: `frontend/src/styles.css`

**步骤 1：更新状态**

- 将 Task 4 状态改为 `in_progress`。

**步骤 2：写入失败验证**

- 先运行 `npm run build` 作为样式改造前基线，并记录结果。

**步骤 3：最小实现**

- 顶栏增加问答/知识库导航，当前路由高亮，使用文本和 lucide 图标。
- 知识库采用桌面双栏、小屏上下布局，与现有工作台保持一致的颜色、边框、圆角和阴影 token。
- 添加空状态、加载状态、成功/失败反馈、可见 label、focus ring、44px 级别触控目标和 `aria-live` 状态区。
- 禁用和删除使用明确的状态文案，不仅依赖颜色传递含义。
- 为 `prefers-reduced-motion` 保留可接受的非动画体验。

**步骤 4：运行 GREEN**

- Run: `cd frontend && npm run build`
- Expected: PASS，无 Vite 编译错误。
- 手工验证 `/`、`/knowledge`、浏览器返回和窄屏布局，并将结果写入 `Green Evidence`。

**步骤 5：提交**

- `git add frontend/src/App.jsx frontend/src/pages/KnowledgePage.jsx frontend/src/styles.css`
- `git commit -m "style: 完善知识库页面导航与交互"`

### Task 5: 完成后端回归与前端构建验收

**状态：** in_progress

**Red Evidence：** 待填写

**Green Evidence：**

- Command: `cd frontend && npm run build`
- Actual: PASS。
- Command: `mvn -q -DskipTests compile`
- Actual: PASS，后端生产代码编译通过。
- Command: `mvn -q -Dtest=KnowledgeControllerTest,KnowledgeServiceTest test`
- Actual: 当前无法绕过同一测试编译阻塞；Task 1 执行时该两项测试曾通过。
- 状态保持 `in_progress`，等待工作区缺失依赖问题处理后再完成全量验收。

**涉及文件：**
- Modify: `.ai/ISSUE-2026-08-25-knowledge-base-page/tasks.md`
- Modify: `.ai/ISSUE-2026-08-25-knowledge-base-page/changelog.md`

**步骤 1：更新状态**

- 将 Task 5 状态改为 `in_progress`。

**步骤 2：运行完整验证**

- Run: `mvn -q test`
- Run: `cd frontend && npm run build`
- Expected: 两项均 PASS。
- 若发现失败，先定位并修复对应 Task，不跳过失败。

**步骤 3：回写证据**

- 将完整测试命令和实际结果写入 `Red Evidence` / `Green Evidence`（本 Task 的 Red 可记录无预期 RED 或先前失败回归）。
- 在 `changelog.md` 记录最终实现与验证结果。
- 将 Task 5 状态改为 `completed`。

**步骤 4：提交**

- `git add .ai/ISSUE-2026-08-25-knowledge-base-page/tasks.md .ai/ISSUE-2026-08-25-knowledge-base-page/changelog.md`
- `git commit -m "test: 验收知识库管理页面"`
