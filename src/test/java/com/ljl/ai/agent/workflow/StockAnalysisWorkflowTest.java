package com.ljl.ai.agent.workflow;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StockAnalysisWorkflowTest {

    @Test
    void shouldBuildAndRunFanOutFanInGraph() {
        StockAnalysisWorkflow workflow = new StockAnalysisWorkflow();
        CompiledGraph<AgentState> graph = workflow.compile();

        assertNotNull(graph);
    }

    @Test
    void shouldRunExecutionStateWithoutPuttingItIntoGraphState() {
        StockAnalysisWorkflow workflow = new StockAnalysisWorkflow();
        ExecutionTask market = ExecutionTask.pending("market", com.ljl.ai.agent.planner.StockAnalysisTask.MARKET_DATA);
        ExecutionTask news = ExecutionTask.pending("news", com.ljl.ai.agent.planner.StockAnalysisTask.NEWS_ANALYSIS);
        market.start();
        market.complete("股票：600519.SH；时间：2026-08-25；价格：1500");
        news.start();
        news.complete("股票：600519.SH；时间：2026-08-25；新闻：经营稳定");
        ExecutionState executionState = ExecutionState.planned(
                "execution-1", "session-1", "分析600519.SH", List.of(market, news));
        executionState.setPlan(com.ljl.ai.agent.planner.AgentPlan.builder().intent("STOCK_ANALYSIS")
                .symbol("600519.SH").tasks(List.of(market.getTaskType(), news.getTaskType())).build());

        ExecutionState result = workflow.run(executionState);

        assertEquals("execution-1", result.getExecutionId());
    }

    @Test
    void shouldRunReflectorAndCriticInsideTheGraph() {
        ExecutionTask market = ExecutionTask.pending("market", com.ljl.ai.agent.planner.StockAnalysisTask.MARKET_DATA);
        ExecutionTask news = ExecutionTask.pending("news", com.ljl.ai.agent.planner.StockAnalysisTask.NEWS_ANALYSIS);
        market.start();
        market.complete("股票：600519.SH；时间：2026-08-25；价格：1500");
        news.start();
        news.complete("股票：600519.SH；时间：2026-08-25；新闻：经营稳定");
        ExecutionState state = ExecutionState.planned("execution-2", "session-1", "分析600519.SH", List.of(market, news));
        state.setPlan(com.ljl.ai.agent.planner.AgentPlan.builder().intent("STOCK_ANALYSIS")
                .symbol("600519.SH").tasks(List.of(market.getTaskType(), news.getTaskType())).build());

        new StockAnalysisWorkflow().run(state);

        assertEquals("ANSWER", state.getCurrentNode());
    }
}
