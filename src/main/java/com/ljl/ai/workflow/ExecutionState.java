package com.ljl.ai.workflow;

import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.research.EvidencePack;
import com.ljl.ai.research.ResearchConclusion;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Document(collection = "agent_execution_states")
public class ExecutionState {

    @Id
    private String executionId;
    private String traceId;
    private String sessionId;
    private String userId;
    private String originalQuestion;
    private AgentPlan plan;
    private AnalysisContext analysisContext;
    private EvidencePack evidencePack;
    private ResearchConclusion researchConclusion;
    private List<ExecutionTask> tasks = new ArrayList<>();
    private WorkflowStatus workflowStatus = WorkflowStatus.PLANNED;
    private String currentNode;
    private String graphVersion;
    private String planHash;
    private String lastCompletedNode;
    private long eventSequence;
    private long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String finalAnswer;
    private String errorMessage;

    public static ExecutionState planned(String executionId, String sessionId,
                                         String originalQuestion, List<ExecutionTask> tasks) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId 不能为空");
        }
        ExecutionState state = new ExecutionState();
        state.executionId = executionId;
        state.sessionId = sessionId;
        state.originalQuestion = originalQuestion;
        state.tasks = tasks == null ? new ArrayList<>() : new ArrayList<>(tasks);
        state.createdAt = LocalDateTime.now();
        state.updatedAt = state.createdAt;
        return state;
    }

    public void start() {
        if (workflowStatus != WorkflowStatus.PLANNED && workflowStatus != WorkflowStatus.PAUSED
                && workflowStatus != WorkflowStatus.RETRYING) {
            throw new IllegalStateException("工作流无法开始: " + workflowStatus);
        }
        workflowStatus = WorkflowStatus.RUNNING;
        touch();
    }

    public void retry(String reason) {
        if (workflowStatus != WorkflowStatus.RUNNING && workflowStatus != WorkflowStatus.FAILED) {
            throw new IllegalStateException("工作流无法重试: " + workflowStatus);
        }
        workflowStatus = WorkflowStatus.RETRYING;
        errorMessage = reason;
        touch();
    }

    public void complete() {
        if (tasks.stream().anyMatch(task -> task.getStatus() != TaskStatus.COMPLETED)) {
            throw new IllegalStateException("仍有未完成任务，不能完成工作流");
        }
        workflowStatus = WorkflowStatus.COMPLETED;
        touch();
    }

    public void fail(String reason) {
        workflowStatus = WorkflowStatus.FAILED;
        errorMessage = reason;
        touch();
    }

    public void checkpointCompleted(String node) {
        checkpointCompleted(node, version);
    }

    void checkpointCompleted(String node, long expectedVersion) {
        currentNode = node;
        lastCompletedNode = node;
        if (version <= expectedVersion) {
            version = expectedVersion + 1;
        }
        updatedAt = LocalDateTime.now();
    }

    private void touch() {
        version++;
        updatedAt = LocalDateTime.now();
    }
}
