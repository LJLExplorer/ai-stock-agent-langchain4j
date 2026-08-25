package com.ljl.ai.agent.service;

import com.ljl.ai.agent.planner.AgentPlan;
import com.ljl.ai.agent.planner.PlanValidator;
import com.ljl.ai.agent.planner.StockAnalysisTask;
import com.ljl.ai.agent.workflow.ExecutionState;
import com.ljl.ai.agent.workflow.WorkflowRunner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
