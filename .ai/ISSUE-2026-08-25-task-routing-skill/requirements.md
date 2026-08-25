# 全局任务路由 Skill 需求

## 目标

新增一个全局 Codex skill，用于判断任务复杂度，避免对简单、明确、低风险任务强制使用 `issue-create`、`issue-breakdown`、`issue-execute`。

## 行为要求

- 简单配置修改、单文件小修复、明确的文档更新、格式调整和局部测试修复，直接执行并验证。
- 跨模块功能、架构调整、需求不明确、多阶段协作或高风险变更，建议使用对应 issue 工作流。
- 用户明确点名 skill 时，优先遵循用户指定。
- 不修改现有 `issue-create`、`issue-breakdown`、`issue-execute` skill。
- 不因创建或更新 skill 本身触发递归的 issue 工作流。

## 验收标准

- 全局目录存在可被 Codex 发现的 `task-routing/SKILL.md`。
- 包含清晰的简单任务/复杂任务边界和用户明确指定 skill 的优先级。
- 通过 skill 格式校验。
