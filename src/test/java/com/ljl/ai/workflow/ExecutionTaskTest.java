package com.ljl.ai.workflow;

import com.ljl.ai.planner.StockAnalysisTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionTaskTest {

    @Test
    void shouldAllowRetryFromCompletedStatus() {
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        task.start();
        task.complete("结果不可信");

        task.retry("结果不可信，需要重试");

        assertEquals(TaskStatus.RETRYING, task.getStatus());
    }

    @Test
    void shouldKeepAllSuccessfulResultsAcrossRetries() {
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        task.start();
        task.complete("第一次行情");
        task.retry("需要重新查询");
        task.start();
        task.complete("第二次行情");

        assertEquals("第二次行情", task.getResult());
        assertEquals(java.util.List.of("第一次行情", "第二次行情"), task.getResultHistory());
    }

    @Test
    void shouldKeepPreviousResultWhenRetryFails() {
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        task.start();
        task.complete("第一次行情");
        task.retry("需要重新查询");
        task.start();
        task.fail("查询失败");

        assertEquals("第一次行情", task.getResult());
        assertTrue(task.getResultHistory().contains("第一次行情"));
    }
}
