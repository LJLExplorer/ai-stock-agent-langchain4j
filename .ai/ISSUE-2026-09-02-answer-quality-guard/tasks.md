# ISSUE-2026-09-02-answer-quality-guard 任务拆解

> **For Claude:** 必需子技能：使用 issue-execute 逐任务实现此计划；执行每个 Task 时必须同时遵循 test-driven-development

**目标：** 为工作流最终答案增加与模型无关的生产级质量防线，阻止退化 Markdown 入库和展示。

**架构：** 工作流先用预算化组件构建可信任务上下文，再调用无会话记忆的专用 AiService 生成答案。纯 Java 质量闸门判定首次输出是否可用；失败时仅重写一次，仍失败则由后端返回确定性摘要。模型升级只影响首次成功率，不影响防护语义。

**技术栈：** Java 21、Spring Boot 3、LangChain4j AiServices、JUnit 5、Mockito、AssertJ、ReactMarkdown/remark-gfm。

**相关文档：**
- 需求文档：`.ai/ISSUE-2026-09-02-answer-quality-guard/requirements.md`
- 设计文档：`.ai/ISSUE-2026-09-02-answer-quality-guard/design.md`
- 变更记录：`.ai/ISSUE-2026-09-02-answer-quality-guard/changelog.md`

**相关规范：**
- 仓库不存在 `yx-coder/AGENT.md`、`yx-coder/规范/架构规范.md`、`yx-coder/规范/编码规范.md`。
- 仓库不存在 `.ai-knowledge/base_knowledge/`；遵循现有 Java、Spring 和测试风格。

**涉及组件：**
- LangChain4j：`src/main/java/com/ljl/ai/agent/AgentConfig.java`
- 工作流：`src/main/java/com/ljl/ai/workflow/StockAnalysisWorkflow.java`
- MongoDB 消息存储协议不变；本 Issue 不修改数据库结构。

### Task 1: 检测退化 Markdown

**状态：** completed

**Red Evidence：**
- Command: `mvn -q -Dtest=AnswerQualityGuardTest test`
- Actual: FAIL（测试编译失败：`AnswerQualityGuard` 类不存在）。
- Match Expected: yes

**Green Evidence：**
- Command: `mvn -q -Dtest=AnswerQualityGuardTest test`
- Actual: PASS（5 个测试：真实退化样本、合法表格与代码块、未闭合围栏、列数不一致及行内代码）。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/workflow/AnswerQualityGuard.java`
- Test: `src/test/java/com/ljl/ai/workflow/AnswerQualityGuardTest.java`

**相关组件：**
- 无外部组件；保持为无副作用纯 Java 逻辑。

**步骤 0：开始任务前更新状态**

- 将本 Task 的 `状态` 从 `pending` 改为 `in_progress`。
- 在状态更新完成前，禁止修改生产代码。

**步骤 1：编写失败测试**

创建 `AnswerQualityGuardTest`，至少写出以下测试：

```java
@Test
void shouldRejectRealDegeneratedMarkdownSample() {
    String answer = "### 实时行情（来源：Market Data）| | | | | | | | | | | | | | | "
            + ":--- |:--- |:--- |:--- |--|:-|--|:-|--|-||最新价||涨跌幅||MA_||趋势判断||"
            + "||||||||||||||||||||||||||||||||||||||||||||||||||||:``````````````";

    AnswerQualityGuard.Validation validation = new AnswerQualityGuard().validate(answer);

    assertThat(validation.valid()).isFalse();
    assertThat(validation.reason())
            .isEqualTo(AnswerQualityGuard.Reason.EXCESSIVE_MARKDOWN_PUNCTUATION);
}

@Test
void shouldAcceptValidGfmAndCodeBlocks() {
    String answer = """
            ## 数据
            | 指标 | 数值 |
            | --- | ---: |
            | 最新价 | 1500 |

            ```text
            price | volume
            ```
            """;

    assertThat(new AnswerQualityGuard().validate(answer).valid()).isTrue();
}
```

继续覆盖：空白答案、未闭合三反引号、合法行内代码、表头/分隔行列数不一致、异常标点单行、重复短片段和疑似结构截断。测试断言稳定的 `Reason` 枚举，不依赖自然语言错误消息。

**步骤 2：运行测试确认失败**

Run: `mvn -q -Dtest=AnswerQualityGuardTest test`

Expected: FAIL，提示 `AnswerQualityGuard` 不存在。

填写 `Red Evidence`：
- Command: `mvn -q -Dtest=AnswerQualityGuardTest test`
- Actual: 记录编译失败摘要。
- Match Expected: yes/no

**步骤 3：编写最小实现**

创建以下公共契约：

```java
public final class AnswerQualityGuard {
    public enum Reason {
        OK,
        EMPTY,
        UNCLOSED_CODE_FENCE,
        INVALID_GFM_TABLE,
        EXCESSIVE_MARKDOWN_PUNCTUATION,
        REPETITIVE_OUTPUT,
        SUSPICIOUS_ENDING
    }

    public record Validation(boolean valid, Reason reason) {
        static Validation pass() { return new Validation(true, Reason.OK); }
        static Validation reject(Reason reason) { return new Validation(false, reason); }
    }

    public Validation validate(String answer) {
        // 按 EMPTY -> 围栏 -> 异常标点 -> 表格 -> 重复 -> 结尾顺序执行高置信规则。
    }
}
```

实现约束：

- 仅把行首/行尾成对的三反引号视为围栏，行内单反引号不参与围栏计数。
- 对疑似 GFM 分隔行检查相邻表头和数据行；忽略转义管道符 `\|`。
- 异常标点规则使用明显高于正常表格的阈值，例如单行管道符或反引号超过 24 个，并结合正文长度避免误判短代码示例。
- 重复检测使用固定窗口或连续相同片段，不引入第三方库，不对自然语言做语义判断。
- 不修复原文，不抛出异常。

**步骤 4：运行测试确认通过**

Run: `mvn -q -Dtest=AnswerQualityGuardTest test`

Expected: PASS。

填写 `Green Evidence`：
- Command: `mvn -q -Dtest=AnswerQualityGuardTest test`
- Actual: 记录测试数量与 PASS。

**步骤 5：回写执行证据并标记完成**

- 填写本 Task 的 `Red Evidence` 和 `Green Evidence`。
- 将状态改为 `completed`，并在 `changelog.md` 记录质量规则。
- 未完成证据回写和状态更新前，不得开始下一个 Task。

**步骤 6：提交**

```bash
git add src/main/java/com/ljl/ai/workflow/AnswerQualityGuard.java \
  src/test/java/com/ljl/ai/workflow/AnswerQualityGuardTest.java \
  .ai/ISSUE-2026-09-02-answer-quality-guard/tasks.md \
  .ai/ISSUE-2026-09-02-answer-quality-guard/changelog.md
git commit -m "feat: 增加工作流答案质量检测"
```

### Task 2: 为工具结果建立上下文预算

**状态：** completed

**Red Evidence：**
- Command: `mvn -q -Dtest=AnswerContextBuilderTest test`
- Actual: FAIL（测试编译失败：`WorkflowAnswerProperties` 类不存在）。
- Match Expected: yes

**Green Evidence：**
- Command: `mvn -q -Dtest=AnswerContextBuilderTest test`
- Actual: PASS（3 个测试：总/单任务预算、最新结果历史和失败原因保留）。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/workflow/WorkflowAnswerProperties.java`
- Create: `src/main/java/com/ljl/ai/workflow/AnswerContextBuilder.java`
- Modify: `src/main/resources/application.example.yml`
- Test: `src/test/java/com/ljl/ai/workflow/AnswerContextBuilderTest.java`

**相关组件：**
- Spring `@ConfigurationProperties(prefix = "workflow.answer")`。

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`，提交该状态更新后再写生产代码。

**步骤 1：编写失败测试**

创建 `AnswerContextBuilderTest`，直接构造属性和任务，不启动 Spring：

```java
@Test
void shouldKeepEveryTaskWithinConfiguredBudgets() {
    WorkflowAnswerProperties properties = new WorkflowAnswerProperties();
    properties.setMaxContextChars(240);
    properties.setMaxTaskChars(100);
    properties.setMaxHistoryEntries(1);
    ExecutionState state = completedStateWithLongMarketAndNewsResults();

    AnswerContextBuilder.Context context = new AnswerContextBuilder(properties).build(state);

    assertThat(context.content()).contains("MARKET_DATA", "NEWS_ANALYSIS", "内容已截断");
    assertThat(context.content()).hasSizeLessThanOrEqualTo(240);
    assertThat(context.truncatedTaskCount()).isEqualTo(2);
}
```

继续覆盖：空任务、失败任务错误信息、优先使用最新历史结果、总预算小于所有任务头部时仍不越界、中文截断不破坏 UTF-16 代理对。

**步骤 2：运行测试确认失败**

Run: `mvn -q -Dtest=AnswerContextBuilderTest test`

Expected: FAIL，提示属性类或构建器不存在。

回写 Red Evidence。

**步骤 3：编写最小实现**

属性类提供以下默认值和校验后的读取值：

```java
@Configuration
@ConfigurationProperties(prefix = "workflow.answer")
@Data
public class WorkflowAnswerProperties {
    private int maxContextChars = 12000;
    private int maxTaskChars = 3500;
    private int maxHistoryEntries = 2;
}
```

构建器契约：

```java
@Component
public final class AnswerContextBuilder {
    public record Context(String content, int originalChars, int truncatedTaskCount) {}

    public Context build(ExecutionState state) {
        // 为每项输出任务类型、状态、尝试次数、错误和最近 N 次结果；先做单任务裁剪，再做总预算裁剪。
    }
}
```

裁剪函数按 Unicode code point 截断，追加 `…（内容已截断）`；所有配置值在使用时限制为正数。`application.example.yml` 增加：

```yaml
workflow:
  answer:
    max-context-chars: ${WORKFLOW_ANSWER_MAX_CONTEXT_CHARS:12000}
    max-task-chars: ${WORKFLOW_ANSWER_MAX_TASK_CHARS:3500}
    max-history-entries: ${WORKFLOW_ANSWER_MAX_HISTORY_ENTRIES:2}
```

**步骤 4：运行测试确认通过**

Run: `mvn -q -Dtest=AnswerContextBuilderTest test`

Expected: PASS。

回写 Green Evidence。

**步骤 5：回写执行证据并标记完成**

- 更新状态为 `completed`，记录实际默认预算和测试结果。

**步骤 6：提交**

```bash
git add src/main/java/com/ljl/ai/workflow/WorkflowAnswerProperties.java \
  src/main/java/com/ljl/ai/workflow/AnswerContextBuilder.java \
  src/main/resources/application.example.yml \
  src/test/java/com/ljl/ai/workflow/AnswerContextBuilderTest.java \
  .ai/ISSUE-2026-09-02-answer-quality-guard
git commit -m "feat: 限制工作流答案上下文规模"
```

### Task 3: 建立无记忆的工作流答案模型入口

**状态：** completed

**Red Evidence：**
- Command: `mvn -q -Dtest=WorkflowAnswerAssistantContractTest test`
- Actual: FAIL（测试编译失败：`WorkflowAnswerAssistant` 接口不存在）。
- Match Expected: yes

**Green Evidence：**
- Command: `mvn -q -Dtest=WorkflowAnswerAssistantContractTest,AgentConfigToolSelectionTest test`
- Actual: PASS（4 个测试；Maven 输出仅包含 Mockito/Byte Buddy 动态 agent 的 JDK 未来兼容性提示）。

**涉及文件：**
- Create: `src/main/java/com/ljl/ai/agent/WorkflowAnswerAssistant.java`
- Modify: `src/main/java/com/ljl/ai/agent/AgentConfig.java`
- Test: `src/test/java/com/ljl/ai/agent/WorkflowAnswerAssistantContractTest.java`

**相关组件：**
- LangChain4j `AiServices`；该 Bean 禁止配置 `ChatMemoryProvider` 和工具。

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`，完成状态提交后再写测试。

**步骤 1：编写失败测试**

创建契约测试，通过反射验证接口方法没有 `@MemoryId` 参数，并验证两个方法都具备系统/用户消息模板：

```java
@Test
void shouldExposeStatelessGenerateAndRewriteContracts() throws Exception {
    Method generate = WorkflowAnswerAssistant.class.getMethod(
            "generate", String.class, String.class);
    Method rewrite = WorkflowAnswerAssistant.class.getMethod(
            "rewrite", String.class, String.class, String.class);

    assertThat(Stream.of(generate.getParameterAnnotations())
            .flatMap(Stream::of)
            .noneMatch(annotation -> annotation.annotationType() == MemoryId.class)).isTrue();
    assertThat(generate.getAnnotation(SystemMessage.class).value()).contains("最多 6 列");
    assertThat(rewrite.getAnnotation(SystemMessage.class).value()).contains("禁止使用表格");
}
```

增加 `AgentConfig` 的包级测试入口或反射断言，确认存在 `workflowAnswerAssistant()` Bean 方法；实现审查必须确认其 builder 链只有 `chatLanguageModel(...)` 与 `build()`。

**步骤 2：运行测试确认失败**

Run: `mvn -q -Dtest=WorkflowAnswerAssistantContractTest test`

Expected: FAIL，提示 `WorkflowAnswerAssistant` 不存在。

回写 Red Evidence。

**步骤 3：编写最小实现**

新增接口：

```java
public interface WorkflowAnswerAssistant {
    @SystemMessage("""
            你负责把已验证的股票分析工具结果整理成简体中文研究摘要。
            先给结论，再给事实依据、风险和不确定性，不得补造数据。
            Markdown 表格最多 6 列，必须包含非空表头、合法分隔行且每行列数一致；
            不能确保表格合法时使用列表。不得输出内部推理过程。
            """)
    @UserMessage("问题：{{question}}\n\n可信任务结果：\n{{context}}")
    String generate(@V("question") String question, @V("context") String context);

    @SystemMessage("""
            上一版答案格式损坏。重新生成简体中文研究摘要；禁止使用表格和代码块，
            只使用短标题、段落和项目列表。保留可信事实，不得补造数据。
            """)
    @UserMessage("问题：{{question}}\n失败原因：{{reason}}\n\n可信任务结果：\n{{context}}")
    String rewrite(@V("question") String question,
                   @V("context") String context,
                   @V("reason") String reason);
}
```

在 `AgentConfig` 中增加：

```java
@Bean
public WorkflowAnswerAssistant workflowAnswerAssistant() {
    return AiServices.builder(WorkflowAnswerAssistant.class)
            .chatLanguageModel(tracingChatLanguageModel())
            .build();
}
```

不得调用 `.chatMemoryProvider(...)`、`.tools(...)` 或 `.maxSequentialToolsInvocations(...)`。

**步骤 4：运行测试确认通过**

Run: `mvn -q -Dtest=WorkflowAnswerAssistantContractTest,AgentConfigToolSelectionTest test`

Expected: PASS。

回写 Green Evidence。

**步骤 5：回写执行证据并标记完成**

- 更新状态为 `completed`，记录无记忆 Bean 的验证方式。

**步骤 6：提交**

```bash
git add src/main/java/com/ljl/ai/agent/WorkflowAnswerAssistant.java \
  src/main/java/com/ljl/ai/agent/AgentConfig.java \
  src/test/java/com/ljl/ai/agent/WorkflowAnswerAssistantContractTest.java \
  .ai/ISSUE-2026-09-02-answer-quality-guard
git commit -m "feat: 隔离工作流答案模型调用"
```

### Task 4: 接入校验、单次重写和安全降级

**状态：** completed

**Red Evidence：**
- Command: `mvn -q -Dtest=WorkflowAnswerGeneratorTest,StockAnalysisWorkflowTest test`
- Actual: FAIL（`WorkflowAnswerGenerator` 仍只有旧构造器和 `generate(ExecutionState, String)` 方法）。
- Match Expected: yes

**Green Evidence：**
- Command: `mvn -q -Dtest=AnswerQualityGuardTest,AnswerContextBuilderTest,WorkflowAnswerGeneratorTest,StockAnalysisWorkflowTest test`
- Actual: PASS（定向回归通过；Mockito/Byte Buddy 仅输出 JDK 动态 agent 未来兼容性提示）。

**涉及文件：**
- Modify: `src/main/java/com/ljl/ai/workflow/WorkflowAnswerGenerator.java`
- Modify: `src/main/java/com/ljl/ai/workflow/StockAnalysisWorkflow.java`
- Test: `src/test/java/com/ljl/ai/workflow/WorkflowAnswerGeneratorTest.java`
- Test: `src/test/java/com/ljl/ai/workflow/StockAnalysisWorkflowTest.java`

**相关组件：**
- 使用 Task 1–3 的 `AnswerQualityGuard`、`AnswerContextBuilder` 和 `WorkflowAnswerAssistant`。

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`，完成状态提交后再修改测试和生产文件。

**步骤 1：编写失败测试**

重写 `WorkflowAnswerGeneratorTest`，使用 Mockito 覆盖以下行为：

```java
@Test
void shouldRewriteOnceWhenFirstAnswerIsInvalid() {
    when(assistant.generate(anyString(), anyString())).thenReturn(MALFORMED_MONGO_SAMPLE);
    when(assistant.rewrite(anyString(), anyString(), eq("EXCESSIVE_MARKDOWN_PUNCTUATION")))
            .thenReturn("## 结论\n\n- 短期谨慎。\n- 注意波动风险。");

    generator.generate(state);

    assertThat(state.getFinalAnswer()).contains("短期谨慎");
    verify(assistant, times(1)).generate(anyString(), anyString());
    verify(assistant, times(1)).rewrite(anyString(), anyString(), anyString());
}
```

继续覆盖：

- 首次合格时不调用 `rewrite`；
- 首次模型抛异常时允许重写一次；
- 重写输出仍不合格时不再调用模型，返回确定性降级答案；
- 两次都抛异常时仍设置非空降级答案；
- 日志包含 executionId、attempt、reason 和长度，但不包含异常回答正文；
- 降级答案列出已完成/失败任务，并提示工具明细仍可查看。

在 `StockAnalysisWorkflowTest` 更新构造依赖，断言 ANSWER 节点调用新的 `generate(ExecutionState)`，不再由工作流拼接无预算的 11k+ 字符结果。

**步骤 2：运行测试确认失败**

Run: `mvn -q -Dtest=WorkflowAnswerGeneratorTest,StockAnalysisWorkflowTest test`

Expected: FAIL，现有生成器仍依赖 `StockAnalysisAssistant` 和外部拼接字符串，也没有质量校验与重写。

回写 Red Evidence。

**步骤 3：编写最小实现**

将生成器改为构造器注入三个依赖：

```java
@Component
public class WorkflowAnswerGenerator {
    private final WorkflowAnswerAssistant assistant;
    private final AnswerContextBuilder contextBuilder;
    private final AnswerQualityGuard qualityGuard;

    public void generate(ExecutionState state) {
        AnswerContextBuilder.Context context = contextBuilder.build(state);
        String first = null;
        AnswerQualityGuard.Validation firstValidation;
        try {
            first = assistant.generate(state.getOriginalQuestion(), context.content());
            firstValidation = qualityGuard.validate(first);
        } catch (RuntimeException exception) {
            firstValidation = null;
        }
        if (firstValidation.valid()) {
            state.setFinalAnswer(AnswerTextFormatter.format(first));
            return;
        }
        // 记录无正文日志，再调用 rewrite 一次；第二次不合格或异常时使用 fallback(state)。
    }
}
```

生成器使用局部字符串 `failureReason` 区分 `MODEL_ERROR` 与质量闸门的枚举原因，不回头扩展 Task 1 的公共契约。降级文案由确定性方法根据 `TaskStatus` 生成，不拼接可能损坏的任务原文。日志使用参数化模板，仅写元数据。

将 `StockAnalysisWorkflow.answer` 简化为：

```java
private void answer(ExecutionState state) {
    if (answerGenerator != null) {
        answerGenerator.generate(state);
    }
    state.complete();
}
```

**步骤 4：运行测试确认通过**

Run: `mvn -q -Dtest=AnswerQualityGuardTest,AnswerContextBuilderTest,WorkflowAnswerGeneratorTest,StockAnalysisWorkflowTest test`

Expected: PASS，Mockito 验证总模型调用次数不超过两次。

回写 Green Evidence。

**步骤 5：回写执行证据并标记完成**

- 将状态改为 `completed`，在 changelog 记录调用链迁移和降级语义。

**步骤 6：提交**

```bash
git add src/main/java/com/ljl/ai/workflow/WorkflowAnswerGenerator.java \
  src/main/java/com/ljl/ai/workflow/StockAnalysisWorkflow.java \
  src/test/java/com/ljl/ai/workflow/WorkflowAnswerGeneratorTest.java \
  src/test/java/com/ljl/ai/workflow/StockAnalysisWorkflowTest.java \
  .ai/ISSUE-2026-09-02-answer-quality-guard
git commit -m "fix: 拦截工作流异常答案并安全降级"
```

### Task 5: 完成生产回归验证

**状态：** pending

**Red Evidence：** 待填写

**Green Evidence：** 待填写

**涉及文件：**
- Modify: `.ai/ISSUE-2026-09-02-answer-quality-guard/tasks.md`
- Modify: `.ai/ISSUE-2026-09-02-answer-quality-guard/changelog.md`
- Test: `src/test/java/com/ljl/ai/service/ChatServiceWorkflowTest.java`（仅在现有测试无法覆盖协议时修改）

**相关组件：**
- MongoDB、Redis、Milvus 接口和前端协议不得发生变化。

**步骤 0：开始任务前更新状态**

- 将状态改为 `in_progress`。

**步骤 1：补充缺失的失败测试**

检查 `ChatServiceWorkflowTest` 是否已经证明工作流答案和工具调用继续通过现有 `ChatResponse` 返回。若契约缺失，先增加测试并运行确认失败；若已有测试足够，记录“无需新增生产代码，RED 为完整回归前的待验证状态”。

**步骤 2：运行定向回归**

Run:

```bash
mvn -q -Dtest=AnswerQualityGuardTest,AnswerContextBuilderTest,WorkflowAnswerAssistantContractTest,WorkflowAnswerGeneratorTest,StockAnalysisWorkflowTest,ChatServiceWorkflowTest test
```

Expected: PASS；若失败，记录为 Red Evidence，并只修复本 Issue 引入的回归。

**步骤 3：运行项目级验证**

Run:

```bash
mvn -q -DskipTests compile
mvn -q test
npm --prefix frontend run build
git diff --check
```

Expected: 后端编译、可运行测试、前端生产构建和空白检查全部通过。基础设施或外部模型导致的既有测试限制必须记录具体测试名和原因，不得伪报通过。

**步骤 4：人工验收检查**

- 使用测试夹具将 MongoDB 真实异常回答传给质量闸门，确认原因码稳定且不会进入持久化路径。
- 检查日志断言或本地日志，确认没有记录回答正文。
- 检查 `git diff --stat`，确认未修改 MongoDB Schema、ChatResponse DTO 和前端消息协议。

**步骤 5：回写执行证据并标记完成**

- 填写所有命令和实际结果，将 Task 5 状态改为 `completed`。
- 在 `changelog.md` 写明实现、测试、配置项和已知限制。

**步骤 6：提交**

```bash
git add .ai/ISSUE-2026-09-02-answer-quality-guard \
  src/test/java/com/ljl/ai/service/ChatServiceWorkflowTest.java
git commit -m "test: 验证工作流答案质量防护"
```
