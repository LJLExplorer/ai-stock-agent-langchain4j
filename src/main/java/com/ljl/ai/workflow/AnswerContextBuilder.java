package com.ljl.ai.workflow;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds a bounded, task-labelled context for final answer generation. */
@Component
public final class AnswerContextBuilder {

    private static final String TRUNCATION_MARKER = "…（内容已截断）";
    private static final int DEFAULT_MAX_CONTEXT_CHARS = 12_000;
    private static final int DEFAULT_MAX_TASK_CHARS = 3_500;
    private static final int DEFAULT_MAX_HISTORY_ENTRIES = 2;

    private final WorkflowAnswerProperties properties;

    public AnswerContextBuilder(WorkflowAnswerProperties properties) {
        this.properties = properties;
    }

    public record Context(String content, int originalChars, int truncatedTaskCount) {
    }

    public Context build(ExecutionState state) {
        List<ExecutionTask> tasks = state == null || state.getTasks() == null
                ? Collections.emptyList() : state.getTasks();
        if (tasks.isEmpty()) {
            return new Context("（没有可用的工作流任务结果）", 0, 0);
        }

        int maxContextChars = positiveOrDefault(properties.getMaxContextChars(), DEFAULT_MAX_CONTEXT_CHARS);
        int maxTaskChars = positiveOrDefault(properties.getMaxTaskChars(), DEFAULT_MAX_TASK_CHARS);
        int maxHistoryEntries = positiveOrDefault(properties.getMaxHistoryEntries(), DEFAULT_MAX_HISTORY_ENTRIES);
        int perTaskContextChars = Math.max(1, maxContextChars / tasks.size());

        List<String> boundedTasks = new ArrayList<>();
        int originalChars = 0;
        int truncatedTaskCount = 0;
        for (ExecutionTask task : tasks) {
            String raw = taskContent(task, maxHistoryEntries);
            originalChars += codePointCount(raw);
            int taskBudget = Math.min(maxTaskChars, perTaskContextChars);
            String bounded = truncate(raw, taskBudget);
            if (!bounded.equals(raw)) {
                truncatedTaskCount++;
            }
            boundedTasks.add(bounded);
        }

        String content = String.join("\n\n", boundedTasks);
        if (codePointCount(content) > maxContextChars) {
            content = truncate(content, maxContextChars);
        }
        return new Context(content, originalChars, truncatedTaskCount);
    }

    private String taskContent(ExecutionTask task, int maxHistoryEntries) {
        String taskType = task == null || task.getTaskType() == null ? "UNKNOWN" : task.getTaskType().name();
        if (task == null) {
            return "### " + taskType + "\n- 状态：UNKNOWN";
        }
        StringBuilder content = new StringBuilder("### ").append(taskType)
                .append("\n- 状态：").append(task.getStatus())
                .append("\n- 尝试次数：").append(task.getAttempts());
        if (task.getErrorMessage() != null && !task.getErrorMessage().isBlank()) {
            content.append("\n- 错误：").append(task.getErrorMessage());
        }
        List<String> history = task.getResultHistory() == null ? List.of() : task.getResultHistory();
        int from = Math.max(0, history.size() - maxHistoryEntries);
        for (int index = from; index < history.size(); index++) {
            String result = history.get(index);
            if (result != null && !result.isBlank()) {
                content.append("\n- 结果：").append(result);
            }
        }
        if (history.isEmpty() && task.getResult() != null && !task.getResult().isBlank()) {
            content.append("\n- 结果：").append(task.getResult());
        }
        return content.toString();
    }

    private String truncate(String value, int maxCodePoints) {
        int count = codePointCount(value);
        if (count <= maxCodePoints) {
            return value;
        }
        int markerCount = codePointCount(TRUNCATION_MARKER);
        if (maxCodePoints <= markerCount) {
            return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
        }
        int end = value.offsetByCodePoints(0, maxCodePoints - markerCount);
        return value.substring(0, end) + TRUNCATION_MARKER;
    }

    private int codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }
}
