# ISSUE-2026-08-24-rag-diagnostic-logging 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 将 RAG 详细诊断从 MongoDB 持久化改为应用日志记录。

**架构：** 保留 `RagTrace` 作为统一诊断数据结构和 `RagTraceService.saveBestEffort` 调用入口。服务移除 `MongoTemplate`，补齐诊断元数据后输出固定事件名的结构化日志，并隔离日志异常。

**技术栈：** Java、Spring Boot、Lombok、SLF4J、JUnit 5、Maven。

**相关文档：**
- 需求文档：`.ai/ISSUE-2026-08-24-rag-diagnostic-logging/requirements.md`
- 设计文档：`.ai/ISSUE-2026-08-24-rag-diagnostic-logging/design.md`

**相关规范：**
- 架构规范：项目未提供 `yx-coder/规范/架构规范.md`
- 编码规范：项目未提供 `yx-coder/规范/编码规范.md`

**涉及组件：**
- 无数据库访问组件；本次移除 RAG 诊断专用 MongoDB 访问。

### Task 1: 将诊断服务改为日志实现

**状态：** completed

**Red Evidence：**

- Command: `mvn -q -Dtest=RagTraceServiceTest test`
- Actual: 编译失败，`RagTraceService` 只有需要 `MongoTemplate` 的构造方法。
- Match Expected: yes

**Green Evidence：**

- Command: `mvn -q -Dtest=RagTraceServiceTest test`
- Actual: PASS；日志输出 `RAG_DIAGNOSTIC` 事件并包含 traceId、query、retrievalCount 等字段。
- Match Expected: yes

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/agent/service/RagTraceService.java`
- Modify: `src/main/java/com/ljl/ai/agent/model/entity/RagTrace.java`
- Test: `src/test/java/com/ljl/ai/agent/service/RagTraceServiceTest.java`

**步骤 0：开始任务前更新状态**

- 将本 Task 的状态改为 `in_progress`。

**步骤 1：编写失败测试**

- 测试服务可在不提供 `MongoTemplate` 的情况下构造并执行 `saveBestEffort`。
- 测试缺少 traceId/createTime 时会补齐，并保留关键诊断字段。

**步骤 2：运行测试确认失败**

Run: `mvn -q -Dtest=RagTraceServiceTest test`

Expected: FAIL，当前服务要求注入 `MongoTemplate`。

填写 Red Evidence。

**步骤 3：编写最小实现**

- 移除 `MongoTemplate` 字段及导入。
- 保留元数据补齐逻辑。
- 将保存动作替换为固定事件名的 INFO 日志，输出全部诊断字段。
- 保留 best-effort 异常隔离。

**步骤 4：运行测试确认通过**

Run: `mvn -q -Dtest=RagTraceServiceTest test`

Expected: PASS。

填写 Green Evidence。

**步骤 5：回写执行证据并标记完成**

- 回写 Red/Green Evidence，并将状态改为 `completed`。

**步骤 6：提交**

- 尝试提交生产代码与测试；若环境仍禁止写 Git 索引，记录阻塞原因。

### Task 2: 执行 RAG 回归验证

**状态：** completed

**Red Evidence：** 不适用：验证任务不新增行为。

**Green Evidence：**

- Command: `mvn -q -Dtest=RagTraceServiceTest,ChatServiceToolLoopExceededTest test`
- Actual: RAG 专项测试通过；工具循环测试受当前 JDK 21 的 Mockito/Byte Buddy 外部代理附加限制失败，与本次改动无关。
- Command: `mvn -q test`
- Actual: PASS（完整测试执行成功）。
- Match Expected: yes（完整测试）

**涉及文件：**
- Verify: `src/main/java/com/ljl/ai/agent/service/ChatService.java`
- Verify: `src/main/java/com/ljl/ai/agent/rag/RagPipelineService.java`

**步骤 0：开始任务前更新状态**

- 将本 Task 的状态改为 `in_progress`。

**步骤 1：运行针对性测试**

Run: `mvn -q -Dtest=RagTraceServiceTest,ChatServiceToolLoopExceededTest test`

Expected: PASS，且无 RAG 诊断 Mongo 写入依赖。

**步骤 2：运行完整测试**

Run: `mvn -q test`

Expected: PASS。

**步骤 3：回写执行证据并标记完成**

- 回写 Green Evidence，并将状态改为 `completed`。
