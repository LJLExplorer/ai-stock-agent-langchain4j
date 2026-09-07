package com.ljl.ai.workflow;

import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.StockAnalysisTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowNodeDeltaTest {
    @Test
    void criticShouldOnlyReturnDecisionAndRouteFromItsRequiredInput() {
        var reflection = new WorkflowReflector.ReflectionDecision(true, List.of(), List.of(), "trusted");
        WorkflowAgentState state = new WorkflowAgentState(WorkflowAgentState.delta(Map.of(
                "executionId", "decision", "reflectionDecision", reflection)));
        StockAnalysisWorkflow workflow = new StockAnalysisWorkflow();

        Map<String, Object> delta = workflow.criticDecision(state);

        assertThat(delta).containsOnlyKeys("criticDecision", "nextNode");
        assertThat(delta.get("nextNode")).isEqualTo("EVIDENCE_PACK");
        assertThat(state.apply(delta).criticDecision().route()).isEqualTo(WorkflowCritic.Route.ANSWER);
        assertThat(state.data()).doesNotContainKeys("criticDecision", "nextNode");
    }

    @Test
    void reflectionAdapterShouldOnlyReturnReflectionOutput() {
        WorkflowAgentState state = planned();
        WorkflowReflector reflector = mock(WorkflowReflector.class);
        when(reflector.reflect(any())).thenAnswer(invocation -> {
            ExecutionState local = invocation.getArgument(0);
            local.getPlan().setSymbol("000001.SZ");
            local.getTasks().clear();
            return new WorkflowReflector.ReflectionDecision(true, List.of(), List.of(), "trusted");
        });
        StockAnalysisWorkflow workflow = new StockAnalysisWorkflow(null, reflector, new WorkflowCritic(), null);

        Map<String, Object> delta = workflow.reflect(state);

        assertThat(delta).containsOnlyKeys("reflectionDecision");
        assertThat(state.apply(delta).tasks()).hasSize(2);
        assertThat(state.toExecutionState().getPlan().getSymbol()).isEqualTo("600519.SH");
    }

    @Test
    void retryShouldOnlyResetSelectedTaskAndLeaveCheckpointMetadataToCommitBoundary() {
        ExecutionState execution = planned().toExecutionState();
        execution.start();
        execution.getTasks().forEach(task -> {
            task.start();
            task.complete("result " + task.getTaskId());
        });
        execution.setReflectionDecision(new WorkflowReflector.ReflectionDecision(false, List.of("news"), List.of(), "retry news"));
        WorkflowAgentState state = WorkflowAgentState.from(execution);

        Map<String, Object> delta = new StockAnalysisWorkflow().retry(state);
        ExecutionState result = state.apply(delta).toExecutionState();

        assertThat(delta).containsOnlyKeys("tasks", "workflowStatus", "retryCount", "errorMessage");
        assertThat((Map<?, ?>) delta.get("tasks")).hasSize(1);
        assertThat(result.getTasks()).extracting(ExecutionTask::getStatus).containsExactly(TaskStatus.COMPLETED, TaskStatus.RETRYING);
        assertThat(result.getTasks().getLast().getResultHistory()).containsExactly("result news");
        assertThat(result.getRetryCount()).isEqualTo(1);
        assertThat(result.getVersion()).isEqualTo(execution.getVersion());
        assertThat(state.toExecutionState()).isEqualTo(execution);
    }

    @Test
    void startShouldReturnStatusWithoutChangingInputOrVersion() {
        WorkflowAgentState state = planned();

        Map<String, Object> delta = new StockAnalysisWorkflow().start(state);

        assertThat(delta).containsExactlyEntriesOf(Map.of("workflowStatus", "RUNNING"));
        assertThat(state.workflowStatus()).isEqualTo(WorkflowStatus.PLANNED);
        assertThat(state.apply(delta).version()).isEqualTo(state.version());
    }

    @Test
    void answerShouldRejectIncompleteTasksBeforeCallingModel() {
        WorkflowAnswerGenerator answer = mock(WorkflowAnswerGenerator.class);
        StockAnalysisWorkflow workflow = new StockAnalysisWorkflow(null, new WorkflowReflector(), new WorkflowCritic(), answer);

        assertThrows(IllegalStateException.class, () -> workflow.answer(planned()));

        verifyNoInteractions(answer);
    }

    @Test
    void answerAdapterShouldPublishVerifiedEvidenceAnswerAndTerminalStatus() {
        ExecutionState execution = planned().toExecutionState();
        execution.getTasks().forEach(task -> {
            task.start();
            WorkflowTestResults.complete(task);
        });
        execution.setErrorMessage("previous failure");
        WorkflowAgentState state = WorkflowAgentState.from(execution);
        WorkflowAnswerGenerator answer = mock(WorkflowAnswerGenerator.class);
        doAnswer(invocation -> {
            ExecutionState local = invocation.getArgument(0);
            local.setFinalAnswer("accepted answer");
            local.getTasks().clear();
            return null;
        }).when(answer).generate(any());

        Map<String, Object> delta = new StockAnalysisWorkflow(null, new WorkflowReflector(), new WorkflowCritic(), answer).answer(state);
        ExecutionState result = state.apply(delta).toExecutionState();

        assertThat(delta).containsOnlyKeys("evidencePack", "finalAnswer", "workflowStatus", "errorMessage");
        assertThat(result.getTasks()).hasSize(2);
        assertThat(result.getFinalAnswer()).isEqualTo("accepted answer");
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.getWorkflowStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(state.toExecutionState()).isEqualTo(execution);
    }

    @Test
    void taskBranchMustRejectWritesToSiblingTasks() {
        WorkflowAgentState state = planned();
        StockAnalysisTaskNode taskNode = mock(StockAnalysisTaskNode.class);
        doAnswer(invocation -> {
            ExecutionState local = invocation.getArgument(0);
            local.getTasks().forEach(task -> {
                task.start();
                task.complete("branch wrote all tasks");
            });
            return null;
        }).when(taskNode).execute(any(), any());
        StockAnalysisWorkflow workflow = new StockAnalysisWorkflow(taskNode, new WorkflowReflector(), new WorkflowCritic(), null);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> workflow.executeTask(state, "MARKET_DATA"));

        assertThat(failure.getMessage()).contains("TASK_BRANCH_WRITE_OUTSIDE_SCOPE");
        assertThat(state.taskSnapshots()).allSatisfy(task -> assertThat(task.getStatus()).isEqualTo(TaskStatus.PLANNED));
    }

    @Test
    void explicitDeltaMustDetachNestedDomainValuesAndSupportClearingFields() {
        AgentPlan plan = AgentPlan.builder().symbol("600519.SH").tasks(new java.util.ArrayList<>()).build();
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("plan", plan);
        fields.put("errorMessage", null);
        Map<String, Object> delta = WorkflowAgentState.delta(fields);
        plan.setSymbol("000001.SZ");
        plan.getTasks().add(StockAnalysisTask.NEWS_ANALYSIS);

        WorkflowAgentState state = planned().apply(Map.of("errorMessage", "old error")).apply(delta);

        assertThat(state.toExecutionState().getPlan().getSymbol()).isEqualTo("600519.SH");
        assertThat(state.toExecutionState().getPlan().getTasks()).isEmpty();
        assertThat(state.toExecutionState().getErrorMessage()).isNull();
        assertThrows(UnsupportedOperationException.class, delta::clear);
    }

    private WorkflowAgentState planned() {
        ExecutionState state = ExecutionState.planned("delta-test", "session", "question", List.of(
                ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA),
                ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS)));
        state.setPlan(AgentPlan.builder().symbol("600519.SH")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA, StockAnalysisTask.NEWS_ANALYSIS)).build());
        return WorkflowAgentState.from(state);
    }
}
