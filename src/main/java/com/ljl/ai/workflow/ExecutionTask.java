package com.ljl.ai.workflow;

import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.research.FinancialFact;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class ExecutionTask {

    private String taskId;
    private StockAnalysisTask taskType;
    private List<String> dependencies = new ArrayList<>();
    private TaskStatus status = TaskStatus.PLANNED;
    private int attempts;
    private String result;
    /** 每次成功完成的结果都保留，避免重试覆盖之前可用的数据。 */
    private List<String> resultHistory = new ArrayList<>();
    /** 每次成功执行得到的结构化证据按 evidenceId 追加去重。 */
    private List<FinancialFact> evidence = new ArrayList<>();
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    private ExecutionTask(String taskId, StockAnalysisTask taskType) {
        this.taskId = taskId;
        this.taskType = taskType;
    }

    public static ExecutionTask pending(String taskId, StockAnalysisTask taskType) {
        if (taskType == null) {
            throw new IllegalArgumentException("任务类型不能为空");
        }
        return new ExecutionTask(taskId, taskType);
    }

    public void start() {
        if (status != TaskStatus.PLANNED && status != TaskStatus.RETRYING
                && status != TaskStatus.FAILED) {
            throw new IllegalStateException("任务无法开始: " + status);
        }
        status = TaskStatus.RUNNING;
        attempts++;
        startedAt = LocalDateTime.now();
    }

    public void retry(String reason) {
        if (status != TaskStatus.RUNNING && status != TaskStatus.FAILED && status != TaskStatus.COMPLETED) {
            throw new IllegalStateException("任务无法重试: " + status);
        }
        status = TaskStatus.RETRYING;
        errorMessage = reason;
    }

    public void complete(String taskResult) {
        complete(taskResult, List.of());
    }

    public void complete(String taskResult, List<FinancialFact> taskEvidence) {
        if (status != TaskStatus.RUNNING) {
            throw new IllegalStateException("任务未运行，不能完成: " + status);
        }
        status = TaskStatus.COMPLETED;
        if (taskResult != null && !taskResult.isBlank()) {
            result = taskResult;
            if (resultHistory == null) {
                resultHistory = new ArrayList<>();
            }
            resultHistory.add(taskResult);
        }
        appendEvidence(taskEvidence);
        completedAt = LocalDateTime.now();
    }

    private void appendEvidence(List<FinancialFact> taskEvidence) {
        if (taskEvidence == null || taskEvidence.isEmpty()) {
            return;
        }
        if (evidence == null) {
            evidence = new ArrayList<>();
        }
        for (FinancialFact fact : taskEvidence) {
            if (fact != null && evidence.stream()
                    .noneMatch(existing -> existing.evidenceId().equals(fact.evidenceId()))) {
                evidence.add(fact);
            }
        }
    }

    public void fail(String reason) {
        if (status != TaskStatus.RUNNING && status != TaskStatus.RETRYING) {
            throw new IllegalStateException("任务无法失败: " + status);
        }
        status = TaskStatus.FAILED;
        errorMessage = reason;
    }
}
