# 变更历史

## 2026-08-25

- 建立 Markdown Planner 解析与 LangGraph 状态序列化修复 Issue。
- 用户确认采用独立文本解析器与执行上下文隔离方案。
- 新增 Markdown/自然语言 Planner 计划解析，支持真实行情 Markdown 中的股票代码与四类任务识别。
- LangGraph 工作流状态改为只传递可序列化标量，避免 `ExecutionState` 触发默认序列化异常。
- 相关编译、单元测试和 `git diff --check` 已通过。
