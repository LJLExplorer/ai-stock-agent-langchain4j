# Reflector 的确定性校验

工作流链路为 `ToolResult → 成功快照 → Schema Validation → Evidence Validation → ReflectionDecision → Critic`。
`ToolResult.success` 只表示调用成功；能否用于回答由校验结果决定。首次执行和幂等恢复都校验持久化的结果快照，不能仅凭 `COMPLETED` 放行。

## 返回协议与证据要求

| 任务 | 工作流返回协议 | 必需指标 / 最少有效证据 | 最大日历日龄 |
| --- | --- | --- | --- |
| 行情 | `StockQuote` | `price` / 1 条 | 10 天 |
| 技术 | `AnalysisToolPayload` | `close`、`changePercent`、`ma5`、`ma20` / 4 条 | 10 天 |
| 财务 | `AnalysisToolPayload` | `revenue`、`netProfit`、`revenueGrowth`、`netProfitGrowth`、`roe`、`operatingCashFlow` / 6 条 | 550 天 |
| 新闻 | `List<NewsItem>` | 标题、摘要、来源、链接、发布时间 / 1 条 | 30 天 |

技术和财务工具在工作流中返回 `BigDecimal` 指标、股票代码、数据日期、披露日期、来源和时间状态。原有供普通 Assistant 使用的文本接口保留，但文本报告不能通过工作流 Schema 校验。`EvidencePackBuilder` 直接将结构化指标映射为 `FinancialFact`，不从展示文案中提取数字。

上述门槛是项目当前的明确策略，并非行业标准。行情与技术的 10 天容差用于覆盖周末和常见长假，不是精确的交易日历；停牌超过该期限也会拒绝。财务以报告期计算日龄，同时约束披露时间。历史回看以 `AnalysisContext.analysisDate` 为截止日，不以运行当日计算日龄。旧入口缺少上下文时使用执行创建日期。

## 校验规则

1. **Schema**：验证 JSON 顶层形状、字段类型和必填字段；数值必须为 JSON number，不能通过字符串强制转换蒙混过关。证据指标必须属于该任务的协议；行情证据只能使用价格、日涨跌幅、成交量和换手率，不能借用其他任务的指标或任意额外数值字段。
2. **标的**：对比工具快照中的 `symbol`、计划和分析上下文。支持六位代码、交易所前缀和后缀的规范化，显式交易所冲突不能忽略。新闻协议没有逐条证券实体字段，只能保证调用使用计划中的查询范围，不声称证明每篇新闻都与该股票相关。
3. **时间**：必须为 `VERIFIED`，且 `asOf` 非空；既检查数据日期，也检查披露/发布时间不晚于分析截止日。新闻和财务必须有发布时间；新闻发布时间与 `asOf` 必须一致。
4. **来源与完整性**：证据必须有 ID、指标、值、采集时间、来源名称和可解析的 HTTP(S) 来源链接。数值证据同时检查单位和币种。来源链接存在不等于已经验证网页内容真实。
5. **数值与一致性**：价格和均线大于零，成交量为非负整数，换手率及营收非负，单日涨跌幅不小于 -100%。利润、现金流、ROE、增长率允许负值，不设置臆造的统一百分比上限。证据中的日期、值、分析来源、披露日期与本次工具快照必须对应。
6. **证据覆盖**：只统计本次尝试的有效证据，按 `evidenceId` 去重，同时检查必需指标。相同 ID 对应不同事实时拒绝放行。有效证据数量是覆盖检查，不代表存在多个独立数据源。

## 决策与重试

`ReflectionDecision.issues` 包含 `taskId / code / field / message`，例如 `SYMBOL_MISMATCH`、`STALE_EVIDENCE`、`NUMERIC_INVALID`、`SOURCE_MISSING`、`EVIDENCE_MISMATCH`、`INSUFFICIENT_EVIDENCE`。

`WorkflowCritic` 按已有规则路由：全部通过才进入 ANSWER，有重试额度则只重试问题任务，额度耗尽进入 FAILED。PLANNED、RUNNING、RETRYING 状态不能被当作可信结果。新闻正文出现“异常”“失败”“exception”本身不会触发失败。

`ExecutionTask.currentEvidence` 保存当前成功快照的证据；`evidence` 和 `resultHistory` 保留历史供审计。空结果不能沿用上一次结果，历史证据也不能补足本次缺失。EvidencePack 从已完成任务的 `currentEvidence` 重建，因此被纠正的旧数据不会继续进入模型证据上下文。

EVIDENCE_PACK、DEEP_RESEARCH 和 ANSWER 节点均重新校验当前任务，并重建证据包。检查点中的 `trusted`、`evidenceHash` 或 `modelView` 都不是信任凭据；直接从模型节点恢复也不能绕过验证。证据包缺失时可以由有效任务重建；任务本身无效时，在调用模型前以 `UNTRUSTED_TOOL_RESULTS` 拒绝执行。重建结果作为节点 delta 提交，不修改输入图状态。

图协议版本升级为 `stock-analysis-v3`。v1/v2 未完成快照不自动迁移，以免从旧 ANSWER 游标恢复时绕过新的 Schema 和证据校验；调用方需发起新执行。已完成历史结果仍可读取。

## 验证与边界

`WorkflowResultValidatorTest` 覆盖协议错误、标的不一致、历史时点、过期/未来数据、未知时间、来源和必填字段缺失、数值格式与范围、证据 ID 冲突、重试与恢复。`StructuredToolWorkflowTest` 使用真实工具和证据映射器、模拟外部数据源，验证结构化财务/技术数据与图内失败重试。

`WorkflowEvidenceBoundaryTest` 覆盖从证据/模型节点恢复、旧 trusted 与当前证据不一致、派生 modelView/hash 被修改、缺失证据包重建，以及模型入口使用重建证据且不修改输入状态。

这些是数据协议、时点、来源元数据、数值约束和证据覆盖的确定性检查，不是对外部数据真伪、新闻语义相关性或投资结论正确性的证明。没有执行在线行情抓取或模型质量评测。
