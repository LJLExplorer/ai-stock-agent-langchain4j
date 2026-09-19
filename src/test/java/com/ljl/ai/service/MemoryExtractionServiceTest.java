package com.ljl.ai.service;

import com.ljl.ai.config.MemoryConfig;
import com.ljl.ai.model.entity.UserLongTermMemory;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.Optional;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemoryExtractionServiceTest {

    @Test
    void shouldPersistCandidateAndConsolidateOnlyExplicitDurablePreference() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        LongTermMemoryService longTermMemoryService = mock(LongTermMemoryService.class);
        when(longTermMemoryService.extractExplicitPreference("以后回答先说风险"))
                .thenReturn(Optional.of(new LongTermMemoryService.ExtractedPreference("以后回答先说风险",
                        UserLongTermMemory.Type.RESPONSE_PREFERENCE, "response.risk_first")));
        AtomicInteger candidateLookups = new AtomicInteger();
        when(mongo.findOne(any(Query.class), eq(UserMemoryCandidate.class))).thenAnswer(invocation ->
                candidateLookups.incrementAndGet() == 1 ? null : UserMemoryCandidate.builder()
                        .status(UserMemoryCandidate.Status.CONSOLIDATED).build());
        MemoryConfig config = new MemoryConfig();
        config.getLongTerm().setGenerateEnabled(true);
        MemoryExtractionService service = new MemoryExtractionService(mongo, longTermMemoryService, config);

        service.process("user-1", "session-1", "message-1", "以后回答先说风险");
        service.process("user-1", "session-1", "message-1", "以后回答先说风险");
        service.process("user-1", "session-1", "message-2", "这次回答简短一点");

        verify(mongo, atLeastOnce()).save(any(UserMemoryCandidate.class));
        verify(longTermMemoryService, times(1)).consolidateExplicitPreference(eq("user-1"), eq("session-1"),
                eq("message-1"), eq("以后回答先说风险"),
                eq(UserLongTermMemory.Type.RESPONSE_PREFERENCE), eq("response.risk_first"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class));
    }
}
