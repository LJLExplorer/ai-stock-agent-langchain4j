package com.ljl.ai.workflow;

import com.ljl.ai.agent.WorkflowAnswerAssistant;
import com.ljl.ai.research.ClaimEvidenceGuard;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.research.ResearchConclusion;
import com.ljl.ai.service.AnswerTextFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/** Generates a bounded workflow answer and prevents malformed model output from being persisted. */
@Slf4j
@Component
public class WorkflowAnswerGenerator {

    private static final String MODEL_ERROR = "MODEL_ERROR";

    private final WorkflowAnswerAssistant assistant;
    private final AnswerContextBuilder contextBuilder;
    private final ClaimEvidenceGuard evidenceGuard;
    private final AnswerQualityGuard qualityGuard;

    public WorkflowAnswerGenerator(WorkflowAnswerAssistant assistant,
                                   AnswerContextBuilder contextBuilder,
                                   AnswerQualityGuard qualityGuard) {
        this(assistant, contextBuilder, new ClaimEvidenceGuard(), qualityGuard);
    }

    @Autowired
    public WorkflowAnswerGenerator(WorkflowAnswerAssistant assistant,
                                   AnswerContextBuilder contextBuilder,
                                   ClaimEvidenceGuard evidenceGuard,
                                   AnswerQualityGuard qualityGuard) {
        this.assistant = assistant;
        this.contextBuilder = contextBuilder;
        this.evidenceGuard = evidenceGuard;
        this.qualityGuard = qualityGuard;
    }

    /**
     * 优先呈现经过校验的深度裁决；标准模式调用无工具助手生成答案，并同时校验证据和文本质量。
     * 标准答案最多重写一次，仍不合格时写入确定性说明，避免无限重试。
     */
    public void generate(ExecutionState state) {
        if (state == null) {
            return;
        }
        AnswerContextBuilder.Context context = contextBuilder.build(state);
        if (presentResearchConclusion(state, context)) {
            return;
        }
        String trustedContext = trustedContext(state, context);
        GenerationAttempt first = firstAttempt(state, context, trustedContext);
        if (first.valid()) {
            state.setFinalAnswer(AnswerTextFormatter.format(first.answer()));
            return;
        }

        GenerationAttempt rewritten = rewriteAttempt(state, context, trustedContext, first.reason());
        if (rewritten.valid()) {
            state.setFinalAnswer(AnswerTextFormatter.format(rewritten.answer()));
            return;
        }

        log.warn("workflow_answer_fallback executionId={}, firstReason={}, retryReason={}, contextLength={}",
                state.getExecutionId(), first.reason(), rewritten.reason(), context.content().length());
        state.setFinalAnswer(fallback(state));
    }

    /**
     * 渲染并复核深度研究结论；深度模式缺少裁决或裁决无效时直接降级，禁止再用普通生成绕过裁决。
     * 返回 true 表示最终回答已在此处处理，调用方无需继续生成。
     */
    private boolean presentResearchConclusion(ExecutionState state, AnswerContextBuilder.Context context) {
        ResearchConclusion conclusion = state.getResearchConclusion();
        if (conclusion == null) {
            if (state.getAnalysisContext() != null
                    && state.getAnalysisContext().researchMode() == AnalysisContext.ResearchMode.DEEP) {
                rejectResearch(state, "MISSING_RESEARCH_CONCLUSION");
                return true;
            }
            return false;
        }
        if (conclusion.rating() == ResearchConclusion.Rating.INSUFFICIENT_DATA) {
            state.setFinalAnswer(researchFailure());
            return true;
        }
        String answer = render(conclusion);
        GenerationAttempt validation = validate(state, context, 0, answer);
        if (!validation.valid()) {
            log.warn("deep_research_answer_rejected executionId={}, reason={}",
                    state.getExecutionId(), validation.reason());
            rejectResearch(state, validation.reason());
            return true;
        }
        state.setFinalAnswer(AnswerTextFormatter.format(answer));
        return true;
    }

    private String researchFailure() {
        return "## 深度投研未通过校验\n\n"
                + "当前证据不足，或模型输出未通过质量与证据校验，本次不提供评级、置信度和仓位建议。\n\n"
                + "已停止生成替代结论，避免将异常内容作为研究结果返回。请核对下方来源与数据限制后重新分析。";
    }

    private void rejectResearch(ExecutionState state, String reason) {
        java.time.LocalDate date = state.getResearchConclusion() != null
                ? state.getResearchConclusion().dataAsOf() : state.getAnalysisContext().analysisDate();
        state.setResearchConclusion(new ResearchConclusion(ResearchConclusion.Rating.INSUFFICIENT_DATA, 0,
                "结论未通过校验，本次不提供投资判断。", List.of(), List.of(), date, true, List.of(reason)));
        state.setFinalAnswer(researchFailure());
    }

    private String render(ResearchConclusion conclusion) {
        String citations = conclusion.evidenceIds().stream()
                .map(id -> "[evidence:" + id + "]")
                .reduce((left, right) -> left + " " + right)
                .map(value -> " " + value)
                .orElse("");
        StringBuilder answer = new StringBuilder("## 深度投研结论\n\n")
                .append("- 评级：").append(conclusion.rating()).append(citations).append('\n')
                .append("- 置信度：").append(Math.round(conclusion.confidence() * 100))
                .append('%').append(citations).append('\n')
                .append("- 数据截止日：").append(conclusion.dataAsOf()).append(citations)
                .append("\n\n### 综合判断\n\n")
                .append(conclusion.summary()).append(citations);
        if (!conclusion.risks().isEmpty()) {
            answer.append("\n\n### 主要风险\n");
            conclusion.risks().forEach(risk -> answer.append("\n- ").append(risk).append(citations));
        }
        if (conclusion.degraded() || !conclusion.limitations().isEmpty()) {
            answer.append("\n\n### 研究限制\n");
            conclusion.limitations().forEach(limitation ->
                    answer.append("\n- ").append(limitation).append(citations));
        }
        return answer.toString();
    }

    private GenerationAttempt firstAttempt(ExecutionState state, AnswerContextBuilder.Context context,
                                           String trustedContext) {
        try {
            String answer = assistant.generate(state.getOriginalQuestion(), trustedContext);
            return validate(state, context, 1, answer);
        } catch (RuntimeException exception) {
            log.warn("workflow_answer_failed executionId={}, attempt=1, reason={}, contextLength={}, errorType={}",
                    state.getExecutionId(), MODEL_ERROR, context.content().length(), exception.getClass().getSimpleName());
            return GenerationAttempt.invalid(MODEL_ERROR);
        }
    }

    private GenerationAttempt rewriteAttempt(ExecutionState state, AnswerContextBuilder.Context context,
                                             String trustedContext, String reason) {
        try {
            String answer = assistant.rewrite(state.getOriginalQuestion(), trustedContext, reason);
            return validate(state, context, 2, answer);
        } catch (RuntimeException exception) {
            log.warn("workflow_answer_failed executionId={}, attempt=2, reason={}, contextLength={}, errorType={}",
                    state.getExecutionId(), MODEL_ERROR, context.content().length(), exception.getClass().getSimpleName());
            return GenerationAttempt.invalid(MODEL_ERROR);
        }
    }

    /** 先检查引用、数字和日期的证据约束，再检查文本质量，统一返回可供有限重写使用的失败原因。 */
    private GenerationAttempt validate(ExecutionState state, AnswerContextBuilder.Context context,
                                       int attempt, String answer) {
        ClaimEvidenceGuard.Validation evidenceValidation = evidenceGuard.validate(answer, state.getEvidencePack());
        if (!evidenceValidation.valid()) {
            log.warn("workflow_answer_rejected executionId={}, attempt={}, reason={}, missingEvidenceIds={}, answerLength={}, contextLength={}",
                    state.getExecutionId(), attempt, evidenceValidation.reason(),
                    evidenceValidation.missingEvidenceIds(), answer == null ? 0 : answer.length(),
                    context.content().length());
            return GenerationAttempt.invalid(evidenceValidation.reason().name());
        }
        AnswerQualityGuard.Validation validation = qualityGuard.validate(answer);
        if (validation.valid()) {
            log.info("workflow_answer_accepted executionId={}, attempt={}, answerLength={}, contextLength={}, truncatedTaskCount={}",
                    state.getExecutionId(), attempt, answer.length(), context.content().length(), context.truncatedTaskCount());
            return GenerationAttempt.valid(answer);
        }
        log.warn("workflow_answer_rejected executionId={}, attempt={}, reason={}, answerLength={}, contextLength={}, truncatedTaskCount={}",
                state.getExecutionId(), attempt, validation.reason(), answer == null ? 0 : answer.length(),
                context.content().length(), context.truncatedTaskCount());
        return GenerationAttempt.invalid(validation.reason().name());
    }

    private String trustedContext(ExecutionState state, AnswerContextBuilder.Context fallbackContext) {
        if (state.getEvidencePack() != null) {
            String evidence = state.getEvidencePack().modelView();
            return evidence == null || evidence.isBlank()
                    ? "当前没有通过校验的事实证据；只能说明证据不足，不得给出评级、数值或仓位建议。" : evidence;
        }
        return fallbackContext.content();
    }

    private String fallback(ExecutionState state) {
        List<ExecutionTask> tasks = state.getTasks() == null ? List.of() : state.getTasks();
        String completed = taskNames(tasks, TaskStatus.COMPLETED);
        String failed = tasks.stream().filter(task -> task.getStatus() != TaskStatus.COMPLETED)
                .map(this::taskName).distinct().reduce((left, right) -> left + "、" + right).orElse("无");
        return "## 分析结果说明\n\n"
                + "最终摘要生成异常，已停止重试以避免展示格式损坏内容。\n\n"
                + "- 已完成任务：" + completed + "\n"
                + "- 未完成任务：" + failed + "\n"
                + "- 请查看下方工具明细和来源信息，以获取已完成任务的原始结果。";
    }

    private String taskNames(List<ExecutionTask> tasks, TaskStatus status) {
        return tasks.stream().filter(task -> task.getStatus() == status).map(this::taskName)
                .distinct().reduce((left, right) -> left + "、" + right).orElse("无");
    }

    private String taskName(ExecutionTask task) {
        return task.getTaskType() == null ? "UNKNOWN" : task.getTaskType().name();
    }

    private record GenerationAttempt(boolean valid, String answer, String reason) {
        static GenerationAttempt valid(String answer) {
            return new GenerationAttempt(true, answer, AnswerQualityGuard.Reason.OK.name());
        }

        static GenerationAttempt invalid(String reason) {
            return new GenerationAttempt(false, null, reason);
        }
    }
}
