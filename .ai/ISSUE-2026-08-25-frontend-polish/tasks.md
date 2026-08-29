# ISSUE-2026-08-25-frontend-polish 任务拆解

> **For Codex:** 本任务按用户要求不采用 TDD；完成后执行前端构建和后端编译校验。

**目标：** 优化前端工作台视觉与滚动体验，并支持安全删除历史会话。

**架构：** 前端通过新增 DELETE API 删除指定用户的会话；后端复用已有 `ChatMemoryService.deleteSession`，先校验归属再删除会话及消息。视觉调整集中在现有 `App.jsx` 与 `styles.css`。

**技术栈：** React、Vite、CSS、Spring Boot、MongoTemplate。

**相关文档：**
- 需求文档：`.ai/ISSUE-2026-08-25-frontend-polish/requirements.md`
- 设计文档：`.ai/ISSUE-2026-08-25-frontend-polish/design.md`

### Task 1: 增加安全的会话删除 API

**状态：** completed

**校验：** `mvn -q -DskipTests compile` 已通过

**涉及文件：**
- Modify: `../../src/main/java/com/ljl/ai/service/ChatService.java`
- Modify: `../../src/main/java/com/ljl/ai/controller/ChatController.java`

### Task 2: 接入历史会话删除交互

**状态：** completed

**校验：** `npm run build` 已通过

**涉及文件：**
- Modify: `frontend/src/App.jsx`

### Task 3: 优化视觉层次与滚动容器

**状态：** completed

**校验：** `npm run build` 已通过

**涉及文件：**
- Modify: `frontend/src/styles.css`

### Task 4: 汇总变更并执行完整校验

**状态：** completed

**校验：** `npm run build`、`mvn -q -DskipTests compile` 均已通过
