package com.ljl.ai.workflow;

import com.ljl.ai.planner.StockAnalysisTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionStateTest {

    @Test
    void shouldTrackTaskStateAndAllowOnlyValidWorkflowTransitions() {
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析茅台", List.of(task));

        state.start();
        task.start();
        task.complete("quote-result");
        state.complete();

        assertEquals(WorkflowStatus.COMPLETED, state.getWorkflowStatus());
        assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertEquals(2L, state.getVersion());
    }

    @Test
    void shouldRejectCompletingTaskBeforeItStarts() {
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);

        assertThrows(IllegalStateException.class, () -> task.complete("result"));
    }

    @Test
    void shouldAllowStartingFailedTaskForRetry() {
        ExecutionTask task = ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS);

        task.start();
        task.fail("新闻接口未配置");
        task.start();

        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(2, task.getAttempts());
    }
}
