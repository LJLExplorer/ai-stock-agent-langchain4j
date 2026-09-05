# Agent 离线评测样本

`agent-eval-cases.json` 固定覆盖 Planner、话题路由、RAG、证据门禁和工作流恢复五层。
默认单元测试必须使用确定性函数适配器，禁止访问模型、网络、实时行情或共享数据库，确保指标可复现。

指标口径：

- `accuracy`：存在 `expectedLabel` 的样本中，标签完全匹配的比例。
- `Recall@K`：每个检索样本 Top-K 命中的相关文档数占期望相关文档数的比例，再按样本平均。
- `nDCG@K`：使用二元相关性计算并按理想排序归一化，再按检索样本平均。
- `citationCoverage`：有证据声明的事实中，带当前证据引用的比例。
- `numericConsistency`：数字事实中，与证据值一致的比例。
- `averageLatencyMillis`：全部样本观测延迟的算术平均值。
- `totalCalls`：适配器报告的模型、工具或恢复调用次数总和。

需要运行真实模型或外部数据源时，必须另建显式 Maven Profile（例如 `online-agent-eval`），配置独立凭据、预算和超时；默认 CI 不得启用该 Profile，也不得隐式访问外部服务。
