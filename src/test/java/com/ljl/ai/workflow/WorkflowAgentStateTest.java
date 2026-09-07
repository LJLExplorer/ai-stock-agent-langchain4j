package com.ljl.ai.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.StockAnalysisTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowAgentStateTest {
    @Test
    void snapshotsAndDeltasMustNotShareMutableDomainObjects() {
        ExecutionState input = planned();
        WorkflowAgentState graph = WorkflowAgentState.from(input);
        ExecutionState branch = graph.toExecutionState();
        branch.getPlan().setSymbol("000001.SZ");
        branch.getTasks().getFirst().start();
        branch.getTasks().getFirst().complete("branch result");

        WorkflowAgentState updated = graph.apply(graph.deltaTo(branch));
        branch.getTasks().getFirst().getResultHistory().clear();
        input.getPlan().setSymbol("000002.SZ");

        assertThat(graph.toExecutionState().getPlan().getSymbol()).isEqualTo("600519.SH");
        assertThat(graph.toExecutionState().getTasks().getFirst().getStatus()).isEqualTo(TaskStatus.PLANNED);
        assertThat(updated.toExecutionState().getTasks().getFirst().getResultHistory()).containsExactly("branch result");
        assertThrows(UnsupportedOperationException.class, () -> graph.tasks().clear());
        Map<?, ?> taskData = (Map<?, ?>) graph.tasks().get("market");
        assertThrows(UnsupportedOperationException.class, taskData::clear);
    }

    @Test
    void mergeDisjointTaskDeltasWithoutLostUpdatesInEitherOrder() {
        WorkflowAgentState graph = WorkflowAgentState.from(planned());
        ExecutionState market = graph.toExecutionState();
        market.getTasks().getFirst().start();
        market.getTasks().getFirst().complete("market result");
        market.setEventSequence(12);
        ExecutionState news = graph.toExecutionState();
        news.getTasks().getLast().start();
        news.getTasks().getLast().complete("news result");
        news.setEventSequence(9);
        var marketDelta = graph.deltaTo(market);
        var newsDelta = graph.deltaTo(news);

        WorkflowAgentState forward = graph.apply(marketDelta).apply(newsDelta);
        WorkflowAgentState reverse = graph.apply(newsDelta).apply(marketDelta);

        assertThat(forward.data()).isEqualTo(reverse.data());
        assertThat(forward.toExecutionState().getTasks()).extracting(ExecutionTask::getResult)
                .containsExactly("market result", "news result");
        assertThat(forward.eventSequence()).isEqualTo(12);
        assertThat(forward.apply(marketDelta).data()).isEqualTo(forward.data());
    }

    @Test
    void serializeRoutingAndRetryStateWithoutLosingResumeCursor() throws Exception {
        ExecutionState execution = planned();
        execution.setReflectionDecision(new WorkflowReflector.ReflectionDecision(false, List.of("market"), List.of(), "retry"));
        execution.setCriticDecision(new WorkflowCritic.Decision(WorkflowCritic.Route.RETRY, "retry"));
        execution.setRetryCount(1);
        execution.setNextNode("RETRY");
        execution.setEventSequence(7);
        ObjectMapper json = new ObjectMapper();
        String snapshot = json.writeValueAsString(WorkflowAgentState.from(execution).data());
        WorkflowAgentState restored = new WorkflowAgentState(json.readValue(snapshot, new TypeReference<Map<String, Object>>() { }));

        assertThat(restored.reflectionDecision().retryTaskIds()).containsExactly("market");
        assertThat(restored.criticDecision().route()).isEqualTo(WorkflowCritic.Route.RETRY);
        assertThat(restored.retryCount()).isEqualTo(1);
        assertThat(restored.nextNode()).isEqualTo("RETRY");
        assertThat(restored.toExecutionState()).isEqualTo(execution);
        assertThat(restored.apply(Map.of("eventSequence", 8L)).eventSequence()).isEqualTo(8);
    }

    private ExecutionState planned() {
        ExecutionState state = ExecutionState.planned("state-test", "session", "question", List.of(
                ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA),
                ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS)));
        state.setPlan(AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA, StockAnalysisTask.NEWS_ANALYSIS)).build());
        return state;
    }
}
