# 全局任务路由 Skill 设计

## 结构

创建全局 skill：`/Users/ljl/.codex/skills/task-routing/`，包含必需的 `SKILL.md` 和初始化生成的 UI 元数据文件。

## 路由策略

skill 采用自动发现，正文只提供任务复杂度判断和优先级规则，不修改其他 skill。默认以最小必要流程执行；当任务具有明显的多阶段、跨模块或高风险特征时，才建议进入 issue 工作流。

## 校验

使用 `skill-creator` 提供的 `quick_validate.py` 校验目录结构、frontmatter 和未完成占位符，并检查最终文件内容。
