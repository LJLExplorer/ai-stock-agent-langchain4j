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

        AgentState result = graph.invoke(Map.of("question", "分析600519.SH")).orElseThrow();

        assertNotNull(result);
        assertEquals("ANSWER", result.value("currentNode").orElseThrow());
    }

    @Test
    void shouldRunExecutionStateWithoutPuttingItIntoGraphState() {
        StockAnalysisWorkflow workflow = new StockAnalysisWorkflow();
        ExecutionState executionState = ExecutionState.planned(
                "execution-1", "session-1", "分析600519.SH", List.of());

        ExecutionState result = workflow.run(executionState);

        assertEquals("execution-1", result.getExecutionId());
    }
}
