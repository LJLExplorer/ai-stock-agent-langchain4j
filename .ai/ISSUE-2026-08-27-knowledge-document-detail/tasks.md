# ISSUE-2026-08-27-knowledge-document-detail 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 为知识文档提供完整正文详情页，并将问答的内部知识来源指向对应详情。

**架构：** 后端在现有 `KnowledgeService` 和 `KnowledgeController` 中增加按 `documentId` 查询活动文档的能力。前端注册 `/knowledge/documents/:documentId`，列表标题与内部来源链接复用该路由；外部网页来源维持原有新标签跳转。

**技术栈：** Spring Boot、MongoTemplate、React、React Router、Vite、lucide-react。

**相关文档：**

- 需求文档：`.ai/ISSUE-2026-08-27-knowledge-document-detail/requirements.md`
- 设计文档：`.ai/ISSUE-2026-08-27-knowledge-document-detail/design.md`

**相关规范：**

- 架构规范：`@./yx-coder/规范/架构规范.md`（仓库未提供，遵循现有 Spring 分层）
- 编码规范：`@./yx-coder/规范/编码规范.md`（仓库未提供，遵循现有项目风格）

**涉及组件：**

- 数据库访问：现有 `MongoTemplate` 与 `KnowledgeDocument`。

## 执行总规则

- 每个 Task 开始时先把状态改为 `in_progress`，状态更新完成前禁止改生产代码。
- 每个 Task 必须先记录 RED，再实现最小代码，再记录 GREEN。
- 每个 Task 完成后回写证据并标为 `completed`，再开始下一项。
- 前端无现成单元测试运行器时，以 `npm run build` 作为最低 GREEN 证据，并执行浏览器手工验证。

### Task 1: 提供单篇活动知识文档查询接口

**状态：** completed

**Red Evidence：**

- Command: `mvn -q -Dtest=KnowledgeServiceTest,KnowledgeControllerTest test`
- Actual: 测试编译失败，`KnowledgeService` 缺少 `findById(String)`，`KnowledgeController` 缺少 `getDocument(String)`。
- Match Expected: yes

**Green Evidence：**

- Command: `mvn -q -Dtest=KnowledgeServiceTest,KnowledgeControllerTest test`
- Actual: PASS。

**涉及文件：**

- Modify: `../../src/main/java/com/ljl/ai/knowledge/KnowledgeService.java:267`
- Modify: `../../src/main/java/com/ljl/ai/controller/KnowledgeController.java:147`
- Modify: `../../src/test/java/com/ljl/ai/knowledge/KnowledgeServiceTest.java`
- Modify: `../../src/test/java/com/ljl/ai/controller/KnowledgeControllerTest.java`

**相关组件：** MongoTemplate

**步骤 0：开始任务前更新状态**

- 将本 Task 状态改为 `in_progress`。

**步骤 1：编写失败测试**

- 在 `KnowledgeServiceTest` 模拟 Mongo 查询，验证 `findById("doc-1")` 返回 `documentId` 为 `doc-1` 且 `deleteStatus` 不为 `DELETED` 的文档。
- 验证查询不到文档时该方法返回空值或 `Optional.empty()`，并在控制器测试中验证 `GET /api/knowledge/documents/missing` 返回 HTTP 404 与 `success=false`。

**步骤 2：运行测试确认失败**

Run: `mvn -q -Dtest=KnowledgeServiceTest,KnowledgeControllerTest test`

Expected: FAIL，`KnowledgeService` 与控制器尚未提供按 ID 查询能力。

填写 `Red Evidence`：命令、实际失败摘要及是否符合预期。

**步骤 3：编写最小实现**

- 在 `KnowledgeService` 增加 `findById(String documentId)`，使用 `Criteria.where("documentId").is(documentId).and("deleteStatus").ne("DELETED")` 查询单个 `KnowledgeDocument`。
- 在 `KnowledgeController` 的类型路由之前增加 `@GetMapping("/documents/{documentId}")`，成功返回文档；查无结果时返回 `ResponseEntity.status(404).body(Map.of("success", false, "errorMessage", "知识文档不存在或已删除"))`。
- 保持现有 `/documents/type/{type}`、禁用、启用、删除接口行为不变。

**步骤 4：运行测试确认通过**

Run: `mvn -q -Dtest=KnowledgeServiceTest,KnowledgeControllerTest test`

Expected: PASS。

**步骤 5：回写执行证据并标记完成**

- 填写 RED/GREEN 证据并标记 `completed`。

**步骤 6：提交**

Run: `git add src/main/java/com/ljl/ai/agent/knowledge/KnowledgeService.java src/main/java/com/ljl/ai/agent/controller/KnowledgeController.java src/test/java/com/ljl/ai/agent/knowledge/KnowledgeServiceTest.java src/test/java/com/ljl/ai/agent/controller/KnowledgeControllerTest.java .ai/ISSUE-2026-08-27-knowledge-document-detail/tasks.md && git commit -m "feat: 支持查询知识文档详情"`

### Task 2: 新增知识文档详情路由和页面

**状态：** completed

**Red Evidence：**

- Command: `cd frontend && npm run build`
- Actual: FAIL，Vite 无法解析 `./pages/KnowledgeDocumentDetailPage.jsx`。
- Match Expected: yes

**Green Evidence：**

- Command: `cd frontend && npm run build`
- Actual: PASS，详情页路由与页面成功打包。
- 手工验证：受当前会话无可用浏览器及本地端口隔离限制，未执行；本地开发服务器输出的地址为 `http://127.0.0.1:5173/`。

**涉及文件：**

- Modify: `frontend/src/App.jsx:44`
- Create: `frontend/src/pages/KnowledgeDocumentDetailPage.jsx`
- Modify: `frontend/src/styles.css`

**相关组件：** React Router、现有 `readResponse` 响应处理约定。

**步骤 0：开始任务前更新状态**

- 将本 Task 状态改为 `in_progress`。

**步骤 1：编写失败验证**

- 在 `App.jsx` 暂时引入尚不存在的 `KnowledgeDocumentDetailPage` 并注册 `/knowledge/documents/:documentId`。

**步骤 2：运行验证确认失败**

Run: `cd frontend && npm run build`

Expected: FAIL，无法解析详情页模块。

**步骤 3：编写最小实现**

- 新建详情页，通过 `useParams()` 获取 `documentId`，在挂载与 ID 变化时请求 `GET /api/knowledge/documents/${encodeURIComponent(documentId)}`。
- 复用 `readResponse` 或抽取不改变语义的共享辅助函数，显示标题、类型、标签、启用状态、分块数、创建/更新时间及 `rawContent`。
- 详情页提供到 `/knowledge` 的 `Link`；为加载、404、一般请求失败分别提供清晰状态和返回入口。
- 在 `App.jsx` 注册详情路由，并添加与现有页面视觉一致且窄屏可读的样式。

**步骤 4：运行验证确认通过**

Run: `cd frontend && npm run build`

Expected: PASS。

**步骤 5：手工验证**

- 直接访问 `/knowledge/documents/<有效-id>` 并刷新，确认完整正文与元数据可见。
- 访问不存在 ID，确认页面显示未找到状态而非空白或崩溃。

**步骤 6：回写执行证据并标记完成**

- 填写 RED/GREEN 证据与手工验证结果，标记 `completed`。

**步骤 7：提交**

Run: `git add frontend/src/App.jsx frontend/src/pages/KnowledgeDocumentDetailPage.jsx frontend/src/styles.css .ai/ISSUE-2026-08-27-knowledge-document-detail/tasks.md && git commit -m "feat: 添加知识文档详情页"`

### Task 3: 将列表标题与问答来源接入详情页

**状态：** completed

**Red Evidence：**

- Command: `rg -n '<h3>\\{document.title|href=\\{source.documentUrl|target="_blank"' frontend/src/components/knowledge/KnowledgeList.jsx frontend/src/App.jsx`
- Actual: 匹配到列表静态标题及统一外链来源实现。
- Match Expected: yes

**Green Evidence：**

- Command: `cd frontend && npm run build`
- Actual: PASS，列表详情链接及来源分流逻辑成功打包。
- 手工验证：受当前会话无可用浏览器及本地端口隔离限制，未执行。

**涉及文件：**

- Modify: `frontend/src/components/knowledge/KnowledgeList.jsx:1-20`
- Modify: `frontend/src/App.jsx:340`
- Modify: `frontend/src/styles.css`

**相关组件：** React Router `Link`。

**步骤 0：开始任务前更新状态**

- 将本 Task 状态改为 `in_progress`。

**步骤 1：编写失败验证**

- 记录当前实现：列表标题为 `h3` 静态文本；`SourceItem` 不论来源类型都用 `documentUrl` 和 `_blank`。

**步骤 2：运行验证确认失败**

Run: `rg -n "<h3>\{document.title|href=\{source.documentUrl|target=\"_blank\"" frontend/src/components/knowledge/KnowledgeList.jsx frontend/src/App.jsx`

Expected: 匹配旧实现，证明内部文档尚无详情链接且会打开新窗口。

**步骤 3：编写最小实现**

- 在 `KnowledgeList` 将有效 `documentId` 的标题包裹为 `Link to={`/knowledge/documents/${encodeURIComponent(document.documentId)}`}`；缺少 ID 时保留不可点击标题。
- 在 `SourceItem` 中，只有 `documentType === 'WEB' && documentUrl` 时渲染外部锚点和 `_blank`；具备 `documentId` 的非网页来源渲染内部 `Link`；两者皆缺失时渲染不可点击来源内容。
- 添加标题链接的悬停、键盘焦点和文本溢出样式，不影响卡片的禁用、启用、删除按钮。

**步骤 4：运行验证确认通过**

Run: `cd frontend && npm run build`

Expected: PASS。

**步骤 5：手工验证**

- 从知识库列表标题进入详情；点击问答中的手动或飞书知识来源，确认当前标签页进入详情。
- 点击 `WEB` 来源，确认仍在新标签页打开外部原文。

**步骤 6：回写执行证据并标记完成**

- 填写 RED/GREEN 证据与手工验证结果，标记 `completed`。

**步骤 7：提交**

Run: `git add frontend/src/App.jsx frontend/src/components/knowledge/KnowledgeList.jsx frontend/src/styles.css .ai/ISSUE-2026-08-27-knowledge-document-detail/tasks.md && git commit -m "fix: 跳转知识来源详情"`

### Task 4: 完成跨层回归验收

**状态：** completed

**Red Evidence：** 不适用，本任务执行回归验证。

**Green Evidence：**

- Command: `mvn -q -Dtest=KnowledgeServiceTest,KnowledgeControllerTest test`
- Actual: PASS。
- Command: `cd frontend && npm run build`
- Actual: PASS。
- 兼容性修复：详情页在单篇查询接口返回 404 时回退到现有文档列表并按 `documentId` 查找，避免未重启后端时出现通用加载失败。

**涉及文件：**

- Modify: `.ai/ISSUE-2026-08-27-knowledge-document-detail/tasks.md`
- Modify: `.ai/ISSUE-2026-08-27-knowledge-document-detail/changelog.md`

**步骤 0：开始任务前更新状态**

- 将本 Task 状态改为 `in_progress`。

**步骤 1：运行后端回归**

Run: `mvn -q -Dtest=KnowledgeServiceTest,KnowledgeControllerTest test`

Expected: PASS；若被现有不相关测试编译阻塞，记录精确错误和已能运行的最小验证。

**步骤 2：运行前端构建**

Run: `cd frontend && npm run build`

Expected: PASS。

**步骤 3：回写执行证据并标记完成**

- 在 `tasks.md` 填写实际命令和结果，将本 Task 标为 `completed`。
- 在 `changelog.md` 记录实现内容、验证结果和任何外部阻塞。

**步骤 4：提交**

Run: `git add .ai/ISSUE-2026-08-27-knowledge-document-detail/tasks.md .ai/ISSUE-2026-08-27-knowledge-document-detail/changelog.md && git commit -m "docs: 验收知识文档详情页"`
