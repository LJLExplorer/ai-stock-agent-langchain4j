# Agent 投研可靠性与深度研究模式变更记录

## 2026-09-05

- 完成当前项目与 LangGraph、TradingAgents、FinRobot、RD-Agent、AgentScope Java、Spring AI Alibaba、OpenAI Agents SDK 和 CrewAI 的方法对比。
- 确认采用保留 LangChain4j/LangGraph4j 的渐进式增强方案，不整体迁移框架。
- 确认多角色审议仅作为可选深度投研模式，默认请求继续使用现有受控流程。
- 确认统一 AnalysisContext、FinancialFact、EvidencePack、ResearchConclusion、ToolExecutionRecord、RunEvent、ResearchDecision 和 Agent Eval 的数据边界。
- 确认逐节点 Checkpoint、point-in-time、Claim–Evidence 校验、SSE、决策复盘及深度模式降级策略。
- 明确不实现自动下单、任意代码执行、分布式 A2A、整体框架迁移，也不修改或提交 `application.yml`。
- 计划审查补充深度投研异步启动接口，使客户端可以在任务完成前获得 executionId 并订阅 SSE；旧同步接口保持兼容。
- 新增不可变 `AnalysisContext` 与统一解析入口；聊天请求兼容可选分析日期和研究模式，并拒绝未来分析日期。
- 新增不可变 `FinancialFact` 和 `EvidencePack`，以稳定 evidenceId、来源时间及 temporalStatus 表达可核验证据；工作流任务在重试时保留并去重历史证据。
- 行情、财务和新闻客户端增加 analysisDate 感知契约：日 K 先按日期截断，财务数据按披露日选择，新闻过滤未来发布时间，未知日期保留 `UNKNOWN`；旧方法继续委托当前日期。
- 行情与技术工具增加 `AnalysisContext` 工作流入口：历史行情由截止日 K 线构造，技术分析只读取截止日之前数据并输出数据截止日；修正技术工具描述，不再宣称尚未计算的 MACD/RSI/KDJ/布林带。
- 财务与新闻工具增加 `AnalysisContext` 工作流入口：财务结果显式输出请求期间、报告期、披露日期、来源和时点状态，新闻结果保留 URL、来源、发布时间和 temporalStatus；历史数据缺失时不回退当前内容。
- 新增确定性 `EvidencePackBuilder`，将行情数值、技术/财务文本与新闻来源映射为 `FinancialFact`，按稳定 evidenceId 分类去重并生成 evidenceHash；未来事实不可引用，未知时点和工具失败进入缺失说明，任务节点同步刷新执行状态证据包。
- 工作流改为逐节点 CAS Checkpoint：每个节点成功后立即更新 lastCompletedNode 并保存，保存失败即停止推进，CRITIC 路由也在返回前落盘；恢复前校验固定 graphVersion 与规范化 planHash，不兼容时返回稳定 `INCOMPATIBLE_CHECKPOINT`。
- 新增工具幂等记录与 Mongo 条件更新 Store，使用 `executionId:taskId:attempt` 唯一键，只允许 STARTED 进入 SUCCEEDED/FAILED；成功时原始结果和证据原子保存，重复相同完成可复用，冲突写入不会覆盖成功记录。
- 任务节点接入工具幂等 Store：执行前查询 attempt，SUCCEEDED 直接恢复且不再次调用工具，四类显式只读工具可从遗留 STARTED 使用下一 attempt 重试，FAILED 遵循上限；只有成功记录持久化后任务才进入 COMPLETED。
