package com.ljl.ai.workflow;

import com.ljl.ai.observability.InMemoryRunEventPublisher;
import com.ljl.ai.observability.RunEvent;
import org.junit.jupiter.api.Test;

import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.StockAnalysisTask;

import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRunnerTest {

    @Test
    void shouldNotOverwriteNewerCheckpointAfterOptimisticLockConflict() {
        StockAnalysisWorkflow workflow = mock(StockAnalysisWorkflow.class);
        ExecutionStateStore stateStore = mock(ExecutionStateStore.class);
        WorkflowRunner runner = new WorkflowRunner(workflow, stateStore);
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析贵州茅台", java.util.List.of());
        state.setPlan(plan());

        when(stateStore.save(state, -1)).thenReturn(state);
        when(stateStore.save(state, 0)).thenThrow(new CheckpointConflictException("exec-1", 0));
        doAnswer(invocation -> {
            StockAnalysisWorkflow.CheckpointCallback callback = invocation.getArgument(1);
            state.checkpointCompleted("INIT");
            callback.save(state, 0);
            return state;
        }).when(workflow).run(eq(state), any());

        assertThrows(CheckpointConflictException.class, () -> runner.run(state));
        verify(stateStore).load("exec-1");
    }

    @Test
    void shouldReplaceAcceptedPlaceholderUsingItsVersion() {
        StockAnalysisWorkflow workflow = mock(StockAnalysisWorkflow.class);
        ExecutionStateStore stateStore = mock(ExecutionStateStore.class);
        ExecutionState placeholder = ExecutionState.planned("exec-accepted", "session-1", "分析贵州茅台", List.of());
        placeholder.setUserId("user-1");
        placeholder.setVersion(3);
        ExecutionState planned = ExecutionState.planned("exec-accepted", "session-1", "分析贵州茅台", List.of());
        planned.setUserId("user-1");
        planned.setPlan(plan());
        when(stateStore.load("exec-accepted")).thenReturn(Optional.of(placeholder));
        when(stateStore.save(planned, 3)).thenReturn(planned);
        when(workflow.run(eq(planned), any())).thenReturn(planned);

        new WorkflowRunner(workflow, stateStore).run(planned);

        assertEquals(3, planned.getVersion());
        verify(stateStore).save(planned, 3);
        verify(stateStore, never()).save(planned, -1);
        verify(workflow).run(eq(planned), any());
    }

    @Test
    void shouldRejectOverwritingExistingNonPlaceholderExecution() {
        StockAnalysisWorkflow workflow = mock(StockAnalysisWorkflow.class);
        ExecutionStateStore stateStore = mock(ExecutionStateStore.class);
        ExecutionState existing = ExecutionState.planned("exec-existing", "session-1", "分析贵州茅台", List.of());
        existing.setUserId("user-1");
        existing.setPlan(plan());
        ExecutionState planned = ExecutionState.planned("exec-existing", "session-1", "分析贵州茅台", List.of());
        planned.setUserId("user-1");
        planned.setPlan(plan());
        when(stateStore.load("exec-existing")).thenReturn(Optional.of(existing));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new WorkflowRunner(workflow, stateStore).run(planned));

        assertEquals("EXECUTION_STATE_ALREADY_EXISTS", error.getMessage());
        verify(stateStore, never()).save(any(), anyLong());
        verify(workflow, never()).run(any(), any());
    }

    @Test
    void shouldPersistInitBeforeExecutingFirstTask() {
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();
        StockAnalysisTaskNode taskNode = mock(StockAnalysisTaskNode.class);
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        doAnswer(invocation -> {
            ExecutionTask current = invocation.getArgument(1);
            current.start();
            current.complete("股票：600519.SH；价格：1500");
            return null;
        }).when(taskNode).execute(any(), eq(task));
        StockAnalysisWorkflow workflow = new StockAnalysisWorkflow(
                taskNode, new WorkflowReflector(), new WorkflowCritic(), null, events);
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        when(store.save(any(), anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
        ExecutionState state = ExecutionState.planned("exec-order", "session-1", "分析", List.of(task));
        state.setPlan(plan());

        new WorkflowRunner(workflow, store, events).run(state);

        var ordered = inOrder(store, taskNode);
        ordered.verify(store).save(state, -1);
        ordered.verify(store).save(state, 0);
        ordered.verify(taskNode).execute(state, task);
        assertEquals("ANSWER", state.getLastCompletedNode());
        List<RunEvent> published = events.snapshot(state.getExecutionId());
        assertEquals(RunEvent.EventType.PLAN_CREATED, published.getFirst().eventType());
        assertTrue(published.getFirst().summary().contains("taskCount=1"));
        assertEquals(RunEvent.EventType.WORKFLOW_COMPLETED, published.getLast().eventType());
        assertEquals(LongStream.rangeClosed(1, published.size()).boxed().toList(),
                published.stream().map(RunEvent::sequence).toList());
        assertEquals(published.getLast().sequence(), state.getEventSequence());
        assertTrue(published.stream().anyMatch(event -> event.eventType() == RunEvent.EventType.NODE_STARTED));
        assertTrue(published.stream().anyMatch(event -> event.eventType() == RunEvent.EventType.NODE_COMPLETED));
    }

    @Test
    void shouldPublishFailureButNotNodeCompletionWhenCheckpointFails() {
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();
        StockAnalysisWorkflow workflow = new StockAnalysisWorkflow(
                null, new WorkflowReflector(), new WorkflowCritic(), null, events);
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        ExecutionState state = ExecutionState.planned("exec-checkpoint", "session-1", "分析", List.of());
        state.setPlan(plan());
        when(store.save(state, -1)).thenReturn(state);
        when(store.save(state, 0)).thenThrow(new CheckpointConflictException("exec-checkpoint", 0));

        assertThrows(RuntimeException.class,
                () -> new WorkflowRunner(workflow, store, events).run(state));

        List<RunEvent> published = events.snapshot(state.getExecutionId());
        assertEquals(List.of(RunEvent.EventType.PLAN_CREATED, RunEvent.EventType.NODE_STARTED,
                        RunEvent.EventType.WORKFLOW_FAILED),
                published.stream().map(RunEvent::eventType).toList());
        assertTrue(published.stream().noneMatch(event -> event.eventType() == RunEvent.EventType.NODE_COMPLETED));
    }

    @Test
    void shouldRejectIncompatibleGraphVersionBeforeResume() {
        StockAnalysisWorkflow workflow = mock(StockAnalysisWorkflow.class);
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        ExecutionState state = ExecutionState.planned("exec-old", "session-1", "分析", List.of());
        state.setPlan(plan());
        state.setGraphVersion("stock-analysis-v0");
        state.setPlanHash(WorkflowRunner.planHash(state.getPlan()));
        when(store.load("exec-old")).thenReturn(java.util.Optional.of(state));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new WorkflowRunner(workflow, store).resume("exec-old"));

        assertTrue(error.getMessage().contains("INCOMPATIBLE_CHECKPOINT"));
        verify(workflow, never()).run(eq(state), any());
    }

    @Test
    void shouldRejectChangedPlanBeforeResume() {
        StockAnalysisWorkflow workflow = mock(StockAnalysisWorkflow.class);
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        ExecutionState state = ExecutionState.planned("exec-plan", "session-1", "分析", List.of());
        state.setPlan(plan());
        state.setGraphVersion(WorkflowRunner.GRAPH_VERSION);
        state.setPlanHash("outdated-plan-hash");
        when(store.load("exec-plan")).thenReturn(java.util.Optional.of(state));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new WorkflowRunner(workflow, store).resume("exec-plan"));

        assertTrue(error.getMessage().contains("INCOMPATIBLE_CHECKPOINT"));
        verify(workflow, never()).run(eq(state), any());
    }

    private AgentPlan plan() {
        return AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA)).build();
    }
}
