# 知识文档详情与来源跳转设计

## 方案选择

采用独立的 React Router 详情路由 `/knowledge/documents/:documentId`，并增加按 ID 查询接口。

相比列表内展开正文，此方案支持直接链接、刷新和问答来源复用；相比模态框，它保留了可分享的稳定地址并避免大型正文挤压管理列表。详情数据按需查询，不在列表接口中返回完整 `rawContent`。

## 数据与接口

- 新增 `GET /api/knowledge/documents/{documentId}`。
- 控制器委托 `KnowledgeService` 查询 `documentId` 对应且未逻辑删除的记录。
- 查询成功返回 `KnowledgeDocument`，包含 `rawContent`；不存在或已删除返回 404，响应包含 `success=false` 与 `errorMessage`。
- 列表接口保持摘要元数据用途，不依赖新增接口的返回结构。

## 前端路由与交互

- `App.jsx` 注册详情路由并渲染 `KnowledgeDocumentDetailPage`。
- `KnowledgeList` 将标题渲染为到详情路由的 `Link`，保留禁用、启用、删除操作。
- 详情页基于 URL 参数加载文档，展示文档元数据和完整正文；提供“返回知识库”链接。
- 加载中显示现有页面一致的加载状态；404 显示未找到状态和返回入口；其他请求失败显示可理解错误与返回入口。

## 问答来源跳转

- `SourceItem` 仅当 `source.documentType === 'WEB'` 且 `documentUrl` 存在时渲染外部锚点，保留 `target="_blank"`。
- 其余来源以 `documentId` 构造内部详情路由，用 React Router `Link` 在当前标签页打开。
- 缺少 `documentId` 的非网页来源不渲染可点击的空链接，保留不可跳转的来源展示。

## 测试与验证

- 后端测试：服务层按 ID 返回活动文档、排除已删除文档；控制器成功返回和不存在时返回 404。
- 前端：验证路由、列表详情链接、内部与网页来源的链接分流；至少运行 `npm run build`。
- 手工验证：从列表及问答来源进入详情，直接访问与刷新详情 URL，检查 404 状态。
