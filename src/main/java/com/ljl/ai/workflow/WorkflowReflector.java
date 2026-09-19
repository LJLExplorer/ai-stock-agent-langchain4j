package com.ljl.ai.workflow;

import com.ljl.ai.planner.StockAnalysisTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用确定性规则检查任务结果，不让模型直接决定不可信数据是否通过。
 */
@Component
public class WorkflowReflector {

    private final WorkflowRetryPolicy retryPolicy;
    private final WorkflowResultValidator resultValidator;

    @Autowired(required = false)
    private NewsRecoveryAdvisor newsRecoveryAdvisor;

    public WorkflowReflector() {
        this(2);
    }

    public WorkflowReflector(int maxAttempts) {
        this(maxAttempts, new WorkflowResultValidator());
    }

    public WorkflowReflector(int maxAttempts, WorkflowResultValidator resultValidator) {
        this(maxAttempts, resultValidator, null);
    }

    WorkflowReflector(int maxAttempts, WorkflowResultValidator resultValidator,
                      NewsRecoveryAdvisor newsRecoveryAdvisor) {
        this.retryPolicy = new WorkflowRetryPolicy(maxAttempts);
        this.resultValidator = resultValidator;
        this.newsRecoveryAdvisor = newsRecoveryAdvisor;
    }

    /**
     * 综合任务终态、结果校验和剩余尝试次数，决定结果是否可信及哪些任务需要重试。
     * 未完成任务或耗尽重试次数会阻止可信判定，并保留结构化问题供后续路由和诊断使用。
     */
    public ReflectionDecision reflect(ExecutionState state) {
        if (state == null || state.getTasks() == null || state.getTasks().isEmpty()) {
            return new ReflectionDecision(false, List.of(), List.of(), "执行状态为空");
        }

        List<String> retryTaskIds = new ArrayList<>();
        List<StockAnalysisTask> additionalTasks = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<WorkflowResultValidator.ValidationIssue> issues = new ArrayList<>();
        Map<String, RecoveryDirective> recoveryDirectives = new LinkedHashMap<>();
        boolean terminalFailure = false;

        for (ExecutionTask task : state.getTasks()) {
            if (task == null || task.getStatus() != TaskStatus.COMPLETED && task.getStatus() != TaskStatus.FAILED) {
                terminalFailure = true;
                issues.add(new WorkflowResultValidator.ValidationIssue(task == null ? null : task.getTaskId(),
                        "TASK_INCOMPLETE", "status", "任务尚未完成"));
                reasons.add("任务尚未完成");
                continue;
            }
            List<WorkflowResultValidator.ValidationIssue> taskIssues = task.getStatus() == TaskStatus.FAILED
                    ? List.of(new WorkflowResultValidator.ValidationIssue(task.getTaskId(),
                    "TOOL_FAILED", "status", "工具执行失败")) : resultValidator.validate(state, task);
            issues.addAll(taskIssues);
            if (!taskIssues.isEmpty()) {
                taskIssues.forEach(issue -> reasons.add(task.getTaskId() + ": " + issue.code()
                        + "(" + issue.field() + ") " + issue.message()));
                if (retryPolicy.canRetry(task)) {
                    NewsRecoveryAdvisor.Advice advice = recoveryAdvice(state, task, taskIssues);
                    if (advice.action() == NewsRecoveryAdvisor.Action.INSUFFICIENT_DATA) {
                        terminalFailure = true;
                        reasons.add(task.getTaskId() + "新闻资料不足，停止重复检索");
                    } else {
                        if (advice.query() != null || advice.action() == NewsRecoveryAdvisor.Action.OFFICIAL_ONLY) {
                            recoveryDirectives.put(task.getTaskId(), new RecoveryDirective(
                                    advice.query(), advice.days(),
                                    advice.action() == NewsRecoveryAdvisor.Action.OFFICIAL_ONLY));
                        }
                        retryTaskIds.add(task.getTaskId());
                    }
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
                reasons.isEmpty() ? "全部任务结果通过校验" : String.join("；", reasons), issues,
                recoveryDirectives);
    }

    private NewsRecoveryAdvisor.Advice recoveryAdvice(ExecutionState state, ExecutionTask task,
                                                       List<WorkflowResultValidator.ValidationIssue> issues) {
        if (newsRecoveryAdvisor == null || task.getTaskType() != StockAnalysisTask.NEWS_ANALYSIS) {
            return NewsRecoveryAdvisor.Advice.none();
        }
        String query = task.getRecoveryQuery() == null ? state.getOriginalQuestion() : task.getRecoveryQuery();
        int days = task.getNewsWindowDays() == null ? 30 : task.getNewsWindowDays();
        return newsRecoveryAdvisor.advise(query, days, task.isNewsOfficialOnly(), issues);
    }

    public record ReflectionDecision(boolean trusted, List<String> retryTaskIds,
                                     List<StockAnalysisTask> additionalTasks, String reason,
                                     List<WorkflowResultValidator.ValidationIssue> issues,
                                     Map<String, RecoveryDirective> recoveryDirectives) {
        public ReflectionDecision {
            issues = issues == null ? List.of() : List.copyOf(issues);
            recoveryDirectives = recoveryDirectives == null ? Map.of() : Map.copyOf(recoveryDirectives);
        }

        public ReflectionDecision(boolean trusted, List<String> retryTaskIds,
                                  List<StockAnalysisTask> additionalTasks, String reason) {
            this(trusted, retryTaskIds, additionalTasks, reason, List.of(), Map.of());
        }

        public ReflectionDecision(boolean trusted, List<String> retryTaskIds,
                                  List<StockAnalysisTask> additionalTasks, String reason,
                                  List<WorkflowResultValidator.ValidationIssue> issues) {
            this(trusted, retryTaskIds, additionalTasks, reason, issues, Map.of());
        }
    }

    public record RecoveryDirective(String query, int days, boolean officialOnly) { }
}
