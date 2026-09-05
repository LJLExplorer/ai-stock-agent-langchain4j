package com.ljl.ai.workflow;

import com.ljl.ai.research.FinancialFact;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * 单次工具 attempt 的不可变幂等记录。
 */
@Document(collection = "agent_tool_executions")
public record ToolExecutionRecord(
        @Id String id,
        String executionId,
        String taskId,
        int attempt,
        Status status,
        String resultSnapshot,
        List<FinancialFact> evidence,
        String errorMessage,
        Instant startedAt,
        Instant completedAt
) {
    public ToolExecutionRecord {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static ToolExecutionRecord started(String executionId, String taskId, int attempt, Instant startedAt) {
        return new ToolExecutionRecord(idOf(executionId, taskId, attempt), executionId, taskId, attempt,
                Status.STARTED, null, List.of(), null, startedAt, null);
    }

    public static ToolExecutionRecord succeeded(String executionId, String taskId, int attempt,
                                                String resultSnapshot, List<FinancialFact> evidence,
                                                Instant completedAt) {
        return new ToolExecutionRecord(idOf(executionId, taskId, attempt), executionId, taskId, attempt,
                Status.SUCCEEDED, resultSnapshot, evidence, null, completedAt, completedAt);
    }

    public static String idOf(String executionId, String taskId, int attempt) {
        if (executionId == null || executionId.isBlank() || taskId == null || taskId.isBlank() || attempt < 1) {
            throw new IllegalArgumentException("工具执行幂等键参数无效");
        }
        return executionId + ":" + taskId + ":" + attempt;
    }

    public enum Status {
        STARTED,
        SUCCEEDED,
        FAILED
    }
}
