package com.ljl.ai.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentPlanTest {

    @Test
    void shouldRepresentStockAnalysisPlanAndMapTasksToTools() {
        AgentPlan plan = AgentPlan.builder()
                .intent("STOCK_ANALYSIS")
                .symbol("600519.SH")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA, StockAnalysisTask.NEWS_ANALYSIS))
                .build();

        assertEquals("STOCK_ANALYSIS", plan.getIntent());
        assertEquals("600519.SH", plan.getSymbol());
        assertEquals("getRealtimeQuote", StockAnalysisTask.MARKET_DATA.toolName());
        assertEquals("searchStockNewsAndAnnouncements", StockAnalysisTask.NEWS_ANALYSIS.toolName());
    }
}
