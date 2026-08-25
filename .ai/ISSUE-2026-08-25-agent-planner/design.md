# Agent Planner 设计

## 架构

请求进入 `ChatService` 后，在调用股票助手前调用无工具的 `AgentPlannerAssistant`。Planner 仅输出结构化 `AgentPlan`，不具备任何业务工具权限。`PlanValidator` 对意图、标的和任务做严格校验，并通过集中式 `StockAnalysisTask` 映射得到允许的工具类别。

通过校验后，服务将计划摘要加入当前用户提示，并选择按任务裁剪后的 `StockAnalysisAssistant`。该助手仍负责调用实际工具和生成最终答案，保留现有 LangChain4j 的参数解析、记忆和工具结果消息机制。

## 领域模型

- `StockAnalysisTask`：`MARKET_DATA`、`TECHNICAL_ANALYSIS`、`FINANCIAL_ANALYSIS`、`NEWS_ANALYSIS`。
- `AgentPlan`：`intent`、`symbol`、`tasks`。
- `PlanValidator`：负责结构合法性、股票代码标准化、任务去重和任务到工具映射；任何未知或越界值都返回无效结果。

## 工具裁剪

`AgentConfig` 提供 Planner Bean、完整工具助手和按工具集合构建助手的能力。任务映射只允许第一版的四个股票分析工具；预测、比较、组合和选股工具不会因 Planner 输出而被注册。

## 降级与错误处理

Planner 的模型异常、解析异常和校验失败只记录日志，并回退到现有完整工具助手。规划层不改变原有非股票对话能力，也不改变对话异常响应和记忆清理逻辑。

## 测试

覆盖模型计划解析、Validator 的合法与非法输入、任务映射和 ChatService 的 Planner 异常降级；运行完整 Maven 测试确认既有行为不回归。
