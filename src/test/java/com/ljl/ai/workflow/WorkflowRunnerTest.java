package com.ljl.ai.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
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

        when(workflow.run(state)).thenReturn(state);
        when(stateStore.save(state, -1)).thenReturn(state);
        when(stateStore.save(state, 0)).thenThrow(new CheckpointConflictException("exec-1", 0));

        assertThrows(CheckpointConflictException.class, () -> runner.run(state));
        verify(stateStore, never()).load("exec-1");
    }
}
