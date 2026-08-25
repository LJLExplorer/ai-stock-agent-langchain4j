# 会话历史与 Markdown 展示设计

## 方案

新增 `POST /api/chat/sessions`，由后端调用已有 `ChatMemoryService.createSession` 创建空的 ACTIVE 会话并返回 `ChatSession`。前端点击新建按钮时调用该接口，避免用假的 UUID 或通过发送空消息创建会话。

前端维护 `sessions`、`sessionFilter` 和 `loadingSessions` 状态。用户 ID 变化时清空当前会话并重新加载 `/api/chat/users/{userId}/sessions`；历史列表使用标题、股票代码和首段 ID 做可识别的展示，列表自身可滚动并支持本地筛选。点击列表项调用已有历史消息接口，恢复消息、sessionId、股票代码和会话详情。

发送成功后重新加载会话列表，以后端保存的标题、消息数量和更新时间为准。当前会话创建后如果用户改变股票代码，保留当前会话 ID，后端消息仍使用当前请求的 orderId。

助手消息引入 `react-markdown` 和 `remark-gfm`，只对 `role === assistant` 的非错误消息渲染 Markdown；用户消息、错误和加载状态保持原有文本/状态渲染。样式覆盖 prose 基础元素、表格、代码块和链接，保证长内容可换行。

## 数据流

```text
新建按钮 -> POST /api/chat/sessions -> ChatSession(sessionId)
                                      -> 当前会话 + 历史列表

用户 ID -> GET /api/chat/users/{userId}/sessions -> 可筛选历史列表
历史项 -> GET /api/chat/sessions/{id}/messages -> 恢复当前对话
发送消息 -> POST /api/chat/send -> 刷新历史列表
助手 content -> ReactMarkdown(remarkGfm) -> HTML 展示
```

## 错误处理

- 创建/加载会话失败时保留已有当前会话和消息，使用提示告知用户。
- userId 为空时不请求接口，提示填写用户 ID。
- 切换 userId 时忽略过期请求结果，避免旧用户列表覆盖新用户列表。
- Markdown 内容异常时保留原始文本作为降级显示。

## 测试

- 后端 controller/service 测试验证创建接口返回新会话及用户参数传递。
- 前端执行 `npm run build`，并用组件逻辑检查会话创建、列表切换、刷新和 Markdown 组件。
