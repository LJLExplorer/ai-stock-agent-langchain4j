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
