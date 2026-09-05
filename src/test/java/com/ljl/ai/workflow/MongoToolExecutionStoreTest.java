package com.ljl.ai.workflow;

import com.ljl.ai.research.FinancialFact;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MongoToolExecutionStoreTest {

    @Test
    void shouldUseStableCompositeIdAndBeginIdempotently() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MongoToolExecutionStore store = new MongoToolExecutionStore(mongoTemplate);
        ToolExecutionRecord started = ToolExecutionRecord.started(
                "execution-1", "market", 1, Instant.parse("2026-09-05T01:00:00Z"));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(ToolExecutionRecord.class))).thenReturn(started);

        ToolExecutionRecord result = store.begin("execution-1", "market", 1);

        assertEquals("execution-1:market:1", result.id());
        assertEquals(ToolExecutionRecord.Status.STARTED, result.status());
    }

    @Test
    void shouldCompleteStartedRecordWithSnapshotAndEvidence() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MongoToolExecutionStore store = new MongoToolExecutionStore(mongoTemplate);
        FinancialFact fact = fact();
        ToolExecutionRecord succeeded = ToolExecutionRecord.succeeded(
                "execution-1", "market", 1, "raw-result", List.of(fact),
                Instant.parse("2026-09-05T01:01:00Z"));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(ToolExecutionRecord.class))).thenReturn(succeeded);

        ToolExecutionRecord result = store.complete(
                "execution-1", "market", 1, "raw-result", List.of(fact));

        assertEquals(ToolExecutionRecord.Status.SUCCEEDED, result.status());
        assertEquals("raw-result", result.resultSnapshot());
        assertEquals(List.of(fact), result.evidence());
    }

    @Test
    void duplicateSuccessIsIdempotentButDifferentResultCannotOverwriteIt() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MongoToolExecutionStore store = new MongoToolExecutionStore(mongoTemplate);
        ToolExecutionRecord existing = ToolExecutionRecord.succeeded(
                "execution-1", "market", 1, "raw-result", List.of(fact()),
                Instant.parse("2026-09-05T01:01:00Z"));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(ToolExecutionRecord.class))).thenReturn(null);
        when(mongoTemplate.findById("execution-1:market:1", ToolExecutionRecord.class)).thenReturn(existing);

        assertEquals(existing, store.complete("execution-1", "market", 1,
                "raw-result", List.of(fact())));
        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> store.complete("execution-1", "market", 1, "different", List.of(fact())));
        assertEquals("TOOL_EXECUTION_CONFLICT", conflict.getMessage());
    }

    @Test
    void succeededRecordCannotTransitionToFailed() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MongoToolExecutionStore store = new MongoToolExecutionStore(mongoTemplate);
        ToolExecutionRecord existing = ToolExecutionRecord.succeeded(
                "execution-1", "market", 1, "raw-result", List.of(),
                Instant.parse("2026-09-05T01:01:00Z"));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(ToolExecutionRecord.class))).thenReturn(null);
        when(mongoTemplate.findById("execution-1:market:1", ToolExecutionRecord.class)).thenReturn(existing);

        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> store.fail("execution-1", "market", 1, "timeout"));

        assertEquals("TOOL_EXECUTION_CONFLICT", conflict.getMessage());
    }

    private FinancialFact fact() {
        LocalDate date = LocalDate.of(2026, 9, 4);
        return new FinancialFact(FinancialFact.EvidenceType.MARKET, "close", "100", "CNY/share", "CNY",
                date.toString(), date, date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                "provider", null, Instant.parse("2026-09-05T01:00:00Z"), null, "snapshot",
                FinancialFact.TemporalStatus.VERIFIED);
    }
}
