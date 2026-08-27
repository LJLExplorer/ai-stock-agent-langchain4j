package com.ljl.ai.agent.workflow;

import com.ljl.ai.agent.planner.StockAnalysisTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionTaskTest {

    @Test
    void shouldAllowRetryFromCompletedStatus() {
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        task.start();
        task.complete("结果不可信");

        task.retry("结果不可信，需要重试");

        assertEquals(TaskStatus.RETRYING, task.getStatus());
    }
}
