# 设计方案

## 方案

新增独立的 Planner 文本解析组件，按“提取 JSON → Markdown/文本结构提取 → 受限关键词推断”的顺序工作。Markdown 解析器识别六位股票代码及 `.SH/.SZ` 后缀，结合标题、列表和正文中的中英文关键词映射四类允许任务；最终仍由 `PlanValidator` 负责意图、代码和任务边界校验。

工作流使用 LangGraph 的状态 Map 传递 `question` 与 `executionId`，不再传递 `ExecutionState`。任务节点通过由 `StockAnalysisWorkflow` 注入的执行上下文读取当前 `ExecutionState`，并在图执行完成后清理上下文。这样默认 `AgentState` 序列化器只处理字符串等简单值，Mongo 状态存储仍负责保存完整业务状态。

## 数据流与错误处理

Planner 原文 → 计划解析 → `PlanValidator` → `ExecutionState` → 执行上下文 + LangGraph 标量状态 → 任务节点更新状态 → Mongo checkpoint。任一解析阶段失败都返回空计划并走完整工具助手；工作流异常继续由 ChatService 统一转换为失败响应，但不会因状态对象序列化失败。

## 测试

覆盖真实 Markdown 示例、JSON 回归、无代码/非法计划安全降级，以及工作流图调用时不把 `ExecutionState` 放入 LangGraph Map 的回归测试。
