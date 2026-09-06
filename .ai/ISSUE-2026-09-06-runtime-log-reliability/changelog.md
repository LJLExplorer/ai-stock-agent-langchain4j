# 变更记录

## 2026-09-06

- 审阅 `Logwork/application.log` 最近一次成功启动后的全部 ERROR/WARN。
- 确认 SSE 404 来自异步句柄返回早于执行状态落库；二次媒体类型异常来自全局 JSON 异常处理器与 SSE `produces` 冲突。
- 确认 Planner 空响应已由受限文本解析兜底，但明确代码请求可直接走确定性白名单规划。
- 确认 Judge 无 JSON 时现有安全降级有效，但原因码过于笼统。
- 确认 LLM 超时在内部重试后恢复、Milvus 在后续重启后恢复；两者属于外部依赖问题。
- 用户批准针对性可靠性修复方案。
- 异步深度研究入口在返回 executionId 前持久化带所有者的 `PLANNED` 占位状态；队列拒绝时将其转为 `FAILED`。
- `WorkflowRunner` 仅通过乐观锁接管合法占位状态，拒绝覆盖已开始或已包含计划的状态。
- SSE 的 `ResponseStatusException` 改为无响应体状态映射，避免 404 后继续触发 `HttpMediaTypeNotAcceptableException`。
- 明确六位股票代码的请求优先使用本地白名单规划，避免无意义 Planner 空响应和任务漂移；公司名请求仍走模型 Planner。
- Judge 继续保持单次调用与确定性安全降级，并区分空响应、缺少 JSON、非法 JSON、评级、置信度和日期；失败日志仅记录类型、原因码和响应长度。
- 验证：后端全量 `mvn -q test` 通过，前端 9 个测试通过，前端生产构建通过，`git diff --check` 通过。
- 未通过代码掩盖的外部问题：Milvus 未启动/未就绪会导致连接失败；上游 MaaS 模型仍可能发生 180 秒网络读取超时。这两类需要依赖服务或网络侧处理。
- 复查 12:59:44 最近一次启动：13:00:05.450 收到深度研究请求，13:00:05.650 卡在检索查询改写模型，直到进程停止都没有 `model_call_finished`；确认这次一分钟无响应发生在 Planner、工具和多角色研究之前。
- 明确股票代码现在同时跳过 Query Rewrite 与 Planner 两次非必要模型调用，直接进入受限本地规划；前端默认股票代码场景不再在工作流启动前等待模型。
- 将异步句柄接收事件从误导性的 `DEEP_RESEARCH_STARTED` 拆成 `EXECUTION_ACCEPTED`，真正进入审议节点时才发送 `DEEP_RESEARCH_STARTED`。
- 六个研究角色和 Judge 均发送真实 `ROLE_STARTED/ROLE_COMPLETED` 事件；前端显示当前角色，并仅按计划、实际工具终态、证据包、7 个审议单元和答案就绪计算完成步数与百分比。
- 新进度条不使用计时器或随机增长：任务数由 `PLAN_CREATED.taskCount` 给出，去重后的后端完成事件推进百分比，成功终态才到 100%。
- 复查 13:17:24 的即时失败：异步占位保存 39 字原问题，`ChatService` 又按 `orderId` 追加股票上下文形成 57 字正式问题，触发严格占位校验的 `EXECUTION_STATE_ALREADY_EXISTS`。
- 提取统一的执行问题构造函数供异步入口与聊天主链路共用；消息已包含同一股票代码时不重复追加。对话失败日志同时输出受限稳定 `errorCode`，不再只显示笼统的 `IllegalStateException`。
