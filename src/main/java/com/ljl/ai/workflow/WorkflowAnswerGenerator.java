package com.ljl.ai.workflow;

import com.ljl.ai.agent.WorkflowAnswerAssistant;
import com.ljl.ai.service.AnswerTextFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/** Generates a bounded workflow answer and prevents malformed model output from being persisted. */
@Slf4j
@Component
public class WorkflowAnswerGenerator {

    private static final String MODEL_ERROR = "MODEL_ERROR";

    private final WorkflowAnswerAssistant assistant;
    private final AnswerContextBuilder contextBuilder;
    private final AnswerQualityGuard qualityGuard;

    public WorkflowAnswerGenerator(WorkflowAnswerAssistant assistant,
                                   AnswerContextBuilder contextBuilder,
                                   AnswerQualityGuard qualityGuard) {
        this.assistant = assistant;
        this.contextBuilder = contextBuilder;
        this.qualityGuard = qualityGuard;
    }

    public void generate(ExecutionState state) {
        if (state == null) {
            return;
        }
        AnswerContextBuilder.Context context = contextBuilder.build(state);
        GenerationAttempt first = firstAttempt(state, context);
        if (first.valid()) {
            state.setFinalAnswer(AnswerTextFormatter.format(first.answer()));
            return;
        }

        GenerationAttempt rewritten = rewriteAttempt(state, context, first.reason());
        if (rewritten.valid()) {
            state.setFinalAnswer(AnswerTextFormatter.format(rewritten.answer()));
            return;
        }

        log.warn("workflow_answer_fallback executionId={}, firstReason={}, retryReason={}, contextLength={}",
                state.getExecutionId(), first.reason(), rewritten.reason(), context.content().length());
        state.setFinalAnswer(fallback(state));
    }

    private GenerationAttempt firstAttempt(ExecutionState state, AnswerContextBuilder.Context context) {
        try {
            String answer = assistant.generate(state.getOriginalQuestion(), context.content());
            return validate(state, context, 1, answer);
        } catch (RuntimeException exception) {
            log.warn("workflow_answer_failed executionId={}, attempt=1, reason={}, contextLength={}, errorType={}",
                    state.getExecutionId(), MODEL_ERROR, context.content().length(), exception.getClass().getSimpleName());
            return GenerationAttempt.invalid(MODEL_ERROR);
        }
    }

    private GenerationAttempt rewriteAttempt(ExecutionState state, AnswerContextBuilder.Context context, String reason) {
        try {
            String answer = assistant.rewrite(state.getOriginalQuestion(), context.content(), reason);
            return validate(state, context, 2, answer);
        } catch (RuntimeException exception) {
            log.warn("workflow_answer_failed executionId={}, attempt=2, reason={}, contextLength={}, errorType={}",
                    state.getExecutionId(), MODEL_ERROR, context.content().length(), exception.getClass().getSimpleName());
            return GenerationAttempt.invalid(MODEL_ERROR);
        }
    }

    private GenerationAttempt validate(ExecutionState state, AnswerContextBuilder.Context context,
                                       int attempt, String answer) {
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
