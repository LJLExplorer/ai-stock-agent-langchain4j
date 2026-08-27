package com.ljl.ai.agent.service;

import com.ljl.ai.agent.planner.AgentPlan;
import com.ljl.ai.agent.planner.PlanValidator;
import com.ljl.ai.agent.planner.StockAnalysisTask;
import com.ljl.ai.agent.workflow.ExecutionState;
import com.ljl.ai.agent.workflow.ExecutionTask;
import com.ljl.ai.agent.workflow.WorkflowRunner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServiceWorkflowTest {

    @Test
    void shouldCreatePersistableExecutionStateFromValidatedPlan() {
        ChatService service = new ChatService();
        PlanValidator.ValidatedPlan plan = new PlanValidator.ValidatedPlan(
                true, null,
                AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                        .tasks(List.of(StockAnalysisTask.MARKET_DATA, StockAnalysisTask.NEWS_ANALYSIS)).build(),
                List.of("getRealtimeQuote", "searchStockNewsAndAnnouncements"));

        ExecutionState state = service.createExecutionState("user-1", "session-1", "分析贵州茅台", plan);

        assertNotNull(state.getExecutionId());
        assertEquals("600519.SH", state.getPlan().getSymbol());
        assertEquals(2, state.getTasks().size());
    }

    @Test
    void shouldExposeExecutionResumeEntryPoint() throws NoSuchMethodException {
        assertNotNull(WorkflowRunner.class.getMethod("resume", String.class));
    }

    @Test
    void shouldExposeWorkflowTasksAsToolInvocationsAndNewsSources() {
        ExecutionTask market = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        market.start();
        market.complete("{\"symbol\":\"600519.SH\"}");
        ExecutionTask news = ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS);
        news.start();
        news.complete("[{\"title\":\"最新公告\",\"url\":\"https://example.com/news\",\"source\":\"示例财经\"}]");
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析", List.of(market, news));
        state.setPlan(AgentPlan.builder().symbol("600519.SH").tasks(List.of()).build());

        var invocations = ChatService.workflowToolInvocations(state);

        assertEquals(2, invocations.size());
        assertTrue(invocations.stream().allMatch(invocation -> Boolean.TRUE.equals(invocation.getSuccess())));
        assertEquals("https://example.com/news", ChatService.extractWebSources(invocations).getFirst().getDocumentUrl());
    }
}
