package com.ljl.ai.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanValidatorTest {

    private final PlanValidator validator = new PlanValidator();

    @Test
    void shouldNormalizeValidPlanAndMapOnlyAllowedTools() {
        AgentPlan plan = AgentPlan.builder()
                .intent("STOCK_ANALYSIS")
                .symbol("600519")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA, StockAnalysisTask.MARKET_DATA,
                        StockAnalysisTask.NEWS_ANALYSIS))
                .build();

        PlanValidator.ValidatedPlan result = validator.validate(plan);

        assertTrue(result.valid());
        assertEquals("600519.SH", result.plan().getSymbol());
        assertEquals(List.of(StockAnalysisTask.MARKET_DATA, StockAnalysisTask.NEWS_ANALYSIS), result.plan().getTasks());
        assertEquals(List.of("getRealtimeQuote", "searchStockNewsAndAnnouncements"), result.toolNames());
    }

    @Test
    void shouldRejectUnknownIntentAndEmptyTasks() {
        assertFalse(validator.validate(AgentPlan.builder()
                .intent("PORTFOLIO_ANALYSIS")
                .symbol("600519.SH")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA))
                .build()).valid());
        assertFalse(validator.validate(AgentPlan.builder()
                .intent("STOCK_ANALYSIS")
                .symbol("600519.SH")
                .tasks(List.of())
                .build()).valid());
    }

    @Test
    void shouldRejectMissingOrMalformedSymbol() {
        assertFalse(validator.validate(AgentPlan.builder()
                .intent("STOCK_ANALYSIS")
                .symbol("")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA))
                .build()).valid());
        assertFalse(validator.validate(AgentPlan.builder()
                .intent("STOCK_ANALYSIS")
                .symbol("not-a-stock")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA))
                .build()).valid());
    }
}
