package com.ljl.ai.workflow;

import org.junit.jupiter.api.Test;

import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.StockAnalysisTask;

import java.util.List;

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
        verify(stateStore, never()).load("exec-1");
    }

    @Test
    void shouldPersistInitBeforeExecutingFirstTask() {
        StockAnalysisTaskNode taskNode = mock(StockAnalysisTaskNode.class);
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        doAnswer(invocation -> {
            ExecutionTask current = invocation.getArgument(1);
            current.start();
            current.complete("股票：600519.SH；价格：1500");
            return null;
        }).when(taskNode).execute(any(), eq(task));
        StockAnalysisWorkflow workflow = new StockAnalysisWorkflow(
                taskNode, new WorkflowReflector(), new WorkflowCritic(), null);
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        when(store.save(any(), anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
        ExecutionState state = ExecutionState.planned("exec-order", "session-1", "分析", List.of(task));
        state.setPlan(plan());

        new WorkflowRunner(workflow, store).run(state);

        var ordered = inOrder(store, taskNode);
        ordered.verify(store).save(state, -1);
        ordered.verify(store).save(state, 0);
        ordered.verify(taskNode).execute(state, task);
        assertEquals("ANSWER", state.getLastCompletedNode());
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
