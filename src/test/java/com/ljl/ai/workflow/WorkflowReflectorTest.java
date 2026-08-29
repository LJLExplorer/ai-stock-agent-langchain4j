package com.ljl.ai.workflow;

import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.StockAnalysisTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowReflectorTest {

    @Test
    void shouldRejectEmptyResultAndScheduleRetry() {
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        task.start();
        task.complete("");
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析", List.of(task));

        WorkflowReflector.ReflectionDecision decision = new WorkflowReflector(2).reflect(state);

        assertFalse(decision.trusted());
        assertEquals(List.of("market"), decision.retryTaskIds());
        assertTrue(decision.reason().contains("为空"));
    }

    @Test
    void shouldNotAddNewsTaskWhenPlanDoesNotRequestNews() {
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        task.start();
        task.complete("股票：600519.SH；时间：2026-08-25；价格：1500");
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "查询行情", List.of(task));
        state.setPlan(AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA)).build());

        WorkflowReflector.ReflectionDecision decision = new WorkflowReflector(2).reflect(state);

        assertTrue(decision.additionalTasks().isEmpty());
        assertTrue(decision.trusted());
    }

    @Test
    void shouldTrustCompleteResultsWithMatchingSymbol() {
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        ExecutionTask news = ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS);
        task.start();
        task.complete("股票：600519.SH；时间：2026-08-25；价格：1500");
        news.start();
        news.complete("股票：600519.SH；时间：2026-08-25；新闻：经营稳定");
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析", List.of(task, news));
        AgentPlan plan = AgentPlan.builder()
                .intent("STOCK_ANALYSIS").symbol("600519.SH")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA, StockAnalysisTask.NEWS_ANALYSIS)).build();
        state.setPlan(plan);

        assertTrue(new WorkflowReflector(2).reflect(state).trusted());
    }

    @Test
    void shouldScheduleRetryForCompletedButUnreliableTaskWithinRetryBudget() {
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        task.start();
        task.complete("查询异常，请稍后重试");
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析", List.of(task));

        WorkflowReflector.ReflectionDecision decision = new WorkflowReflector(2).reflect(state);

        assertFalse(decision.trusted());
        assertEquals(List.of("market"), decision.retryTaskIds());
    }

    @Test
    void shouldTreatCompletedButUnreliableTaskAsTerminalFailureWhenRetriesExhausted() {
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        task.start();
        task.retry("上一轮结果不可信");
        task.start();
        task.complete("查询异常，请稍后重试");
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析", List.of(task));

        WorkflowReflector.ReflectionDecision decision = new WorkflowReflector(2).reflect(state);

        assertFalse(decision.trusted());
        assertTrue(decision.retryTaskIds().isEmpty());
        assertTrue(decision.reason().contains("超过最大重试次数"));
    }
}
