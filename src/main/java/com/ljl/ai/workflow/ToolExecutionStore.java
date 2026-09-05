package com.ljl.ai.workflow;

import com.ljl.ai.research.FinancialFact;

import java.util.List;
import java.util.Optional;

public interface ToolExecutionStore {

    ToolExecutionRecord begin(String executionId, String taskId, int attempt);

    Optional<ToolExecutionRecord> find(String executionId, String taskId, int attempt);

    ToolExecutionRecord complete(String executionId, String taskId, int attempt,
                                 String resultSnapshot, List<FinancialFact> evidence);

    ToolExecutionRecord fail(String executionId, String taskId, int attempt, String errorMessage);
}
