# Markdown Planner 与工作流状态修复

## 背景

Planner 偶尔返回包含标题、加粗、列表和免责声明的 Markdown，而不是 JSON。当前实现只能做有限文本推断；当启用 LangGraph 工作流时，还把不可序列化的 `ExecutionState` 放入图状态，导致请求以 `NotSerializableException` 失败。

## 需求

1. 支持从常见 Markdown/自然语言 Planner 返回中提取股票代码、分析任务和可选意图，并继续经过现有 `PlanValidator` 安全校验。
2. 保留合法 JSON 及 JSON 外包裹说明文字的现有解析行为。
3. Markdown 解析失败时必须安全降级，不得因为解析异常直接造成请求失败。
4. LangGraph 图状态不得携带 `ExecutionState` 对象；工作流执行仍需能更新、持久化并恢复执行状态。
5. 股票分析工作流成功路径和失败路径均不得再因默认图状态序列化触发 `NotSerializableException`。

## 验收标准

- 给定包含 `600511.SH`、实时行情、技术分析、财务分析、新闻/购买建议的 Markdown，能得到合法的 `ValidatedPlan`。
- 合法 JSON 解析测试继续通过。
- 工作流执行传入的图状态只包含可序列化的标量/字符串上下文，执行状态可通过执行上下文取得。
- 相关单元测试通过，且工作区无由本 Issue 引入的编译错误。
