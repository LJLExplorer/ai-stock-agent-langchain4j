package com.ljl.ai.workflow;

import com.ljl.ai.planner.StockAnalysisTask;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 使用确定性规则检查任务结果，不让模型直接决定不可信数据是否通过。
 */
@Component
public class WorkflowReflector {

    private final WorkflowRetryPolicy retryPolicy;

    public WorkflowReflector() {
        this(2);
    }

    public WorkflowReflector(int maxAttempts) {
        this.retryPolicy = new WorkflowRetryPolicy(maxAttempts);
    }

    public ReflectionDecision reflect(ExecutionState state) {
        if (state == null || state.getTasks() == null || state.getTasks().isEmpty()) {
            return new ReflectionDecision(false, List.of(), List.of(), "执行状态为空");
        }

        List<String> retryTaskIds = new ArrayList<>();
        List<StockAnalysisTask> additionalTasks = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        String expectedSymbol = state.getPlan() == null ? null : state.getPlan().getSymbol();
        boolean terminalFailure = false;

        for (ExecutionTask task : state.getTasks()) {
            if (task.getStatus() == TaskStatus.FAILED
                    || (task.getStatus() == TaskStatus.COMPLETED && !reliable(task, expectedSymbol))) {
                if (retryPolicy.canRetry(task)) {
                    retryTaskIds.add(task.getTaskId());
                    reasons.add(task.getTaskId() + "结果为空或不可信");
                } else {
                    terminalFailure = true;
                    reasons.add(task.getTaskId() + "超过最大重试次数");
                }
            }
        }

        // 不自动为行情/技术/财务查询追加新闻，避免无关搜索结果污染直接回答。
        // 新闻任务必须由 Planner 明确提出并经过 PlanValidator 放行。
        boolean trusted = retryTaskIds.isEmpty() && additionalTasks.isEmpty() && !terminalFailure;
        return new ReflectionDecision(trusted, retryTaskIds, additionalTasks,
                reasons.isEmpty() ? "全部任务结果通过校验" : String.join("；", reasons));
    }

    private boolean reliable(ExecutionTask task, String expectedSymbol) {
        String result = task.getResult();
        if (!StringUtils.hasText(result) || result.toUpperCase().contains("ERROR")
                || result.contains("失败") || result.contains("异常") || result.contains("exception")) {
            return false;
        }
        if (expectedSymbol != null && result.contains("股票：") && !result.contains(expectedSymbol)) {
            return false;
        }
        return true;
    }

    public record ReflectionDecision(boolean trusted, List<String> retryTaskIds,
                                     List<StockAnalysisTask> additionalTasks, String reason) {
    }
}
