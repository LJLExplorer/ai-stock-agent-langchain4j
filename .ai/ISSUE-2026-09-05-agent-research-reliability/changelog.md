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
- 新增类型化 `RunEvent` 与进程内发布器：不同 executionId 独立生成连续序号，每次执行只保留最近 200 条事件，支持快照回放及可取消订阅；事件仅允许固定类型和 500 字符以内摘要，不提供 Prompt、响应或工具正文载荷字段。
- 工作流入口、节点包装器及工具节点接入统一 RunEvent：初始状态持久化后发布 PLAN，节点完成事件严格晚于成功 Checkpoint，重试与正常/异常终态均可观察；工具事件仅含固定工具名、截断任务标识、状态、attempt、耗时及受控 errorCode，事件序号同步回写 ExecutionState。
- 新增异步深度研究执行服务：仅接受 DEEP 请求，必要时先创建会话并预分配 executionId，再通过代码内固定大小、有界队列的线程池执行；队列满返回稳定错误码，关闭服务会释放线程池，后台异常保存 FAILED 检查点并发布不含异常正文的终态事件。ChatService 新增包内预分配 ID 入口，原同步 `chat(request)` 行为保持不变。
- 新增 `/api/research/executions` 异步启动、所有者状态查询和 SSE 事件流接口；未授权与不存在的执行统一不可见。SSE 先回放有界快照，再通过 sequence 游标原子补发订阅间隙事件并去重，终态自动完成，断连/超时/发送失败只注销监听器而不取消后台研究。
- 新增确定性 Claim–Evidence Guard，以 `[evidence:ev-…]` 显式语法校验回答只引用当前 EvidencePack；拒绝未知/跨包 ID、无证据引用的数值以及晚于 dataAsOf 的日期。工作流回答改为先证据校验、再 Markdown 校验，只允许一次受原因约束的重写，仍失败则确定性降级；旧 `factCheck` 兼容代码未被当作事实验证接入。
- 新增无工具、无 MemoryId 的深度研究 Assistant 契约，以及基本面→技术面→新闻→看多→看空→风险→Judge 的固定有界编排；所有角色共享同一 EvidencePack 文本且每个最多调用一次。Judge JSON 映射为不可变 ResearchConclusion，校验 rating、confidence、evidenceIds 和 dataAsOf；单角色失败继续但标记降级，Judge 解析或证据越界时返回确定性 `INSUFFICIENT_DATA`。
