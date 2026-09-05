package com.ljl.ai.workflow;

import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.research.FinancialFact;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void shouldAppendEvidenceAcrossSuccessfulRetries() {
        FinancialFact first = marketFact("close", "1488.00");
        FinancialFact second = marketFact("volume", "3210000");
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);

        task.start();
        task.complete("第一次行情", List.of(first));
        task.retry("补充成交量");
        task.start();
        task.complete("第二次行情", List.of(first, second));

        assertEquals(List.of(first, second), task.getEvidence());
    }

    @Test
    void restoredFailedAttemptShouldHonorRetryLimit() {
        ExecutionTask task = ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS);

        task.restoreFailure(2, "provider failed");

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals(2, task.getAttempts());
        assertFalse(new WorkflowRetryPolicy(2).canRetry(task));
    }

    private FinancialFact marketFact(String metric, String value) {
        return new FinancialFact(
                FinancialFact.EvidenceType.MARKET,
                metric,
                value,
                null,
                "CNY",
                "2026-09-04",
                LocalDate.of(2026, 9, 4),
                Instant.parse("2026-09-04T07:00:00Z"),
                "market-provider",
                null,
                Instant.parse("2026-09-05T01:00:00Z"),
                null,
                "snapshot-1",
                FinancialFact.TemporalStatus.VERIFIED
        );
    }
}
