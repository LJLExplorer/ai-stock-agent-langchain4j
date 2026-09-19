package com.ljl.ai.service;

import com.ljl.ai.config.MemoryConfig;
import com.ljl.ai.memory.ChatMemoryService;
import com.ljl.ai.model.entity.ChatSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryJobWorkerTest {

    @Test
    void shouldCompleteClaimedJobWithoutBlockingTheChatRequest() {
        MemoryJobStore store = mock(MemoryJobStore.class);
        MemoryExtractionService extraction = mock(MemoryExtractionService.class);
        ChatMemoryService chatMemory = mock(ChatMemoryService.class);
        MemoryConfig config = new MemoryConfig();
        config.getLongTerm().setGenerateEnabled(true);
        MemoryProcessingJob job = MemoryProcessingJob.builder().jobId("job-1").userId("user-1")
                .sessionId("session-1").messageId("message-1").content("以后先说风险")
                .sourceOccurredAt(LocalDateTime.of(2026, 9, 19, 10, 0)).attempts(1).build();
        when(store.claim(anyString(), any(Instant.class), anyInt())).thenReturn(Optional.of(job), Optional.empty());
        when(extraction.process("user-1", "session-1", "message-1", "以后先说风险",
                LocalDateTime.of(2026, 9, 19, 10, 0)))
                .thenReturn(MemoryExtractionService.Outcome.CONSOLIDATED);
        when(chatMemory.getSession("session-1")).thenReturn(ChatSession.builder().userId("user-1").build());

        new MemoryJobWorker(store, extraction, chatMemory, config).drain();

        verify(store).complete(eq("job-1"), anyString(), eq(MemoryProcessingJob.Status.SUCCEEDED), any(Instant.class));
    }

    @Test
    void shouldRetryFailedJobUntilTheAttemptLimit() {
        MemoryJobStore store = mock(MemoryJobStore.class);
        MemoryExtractionService extraction = mock(MemoryExtractionService.class);
        ChatMemoryService chatMemory = mock(ChatMemoryService.class);
        MemoryConfig config = new MemoryConfig();
        config.getLongTerm().setGenerateEnabled(true);
        config.getLongTerm().setJobMaxAttempts(3);
        MemoryProcessingJob job = MemoryProcessingJob.builder().jobId("job-1").userId("user-1")
                .sessionId("session-1").messageId("message-1").content("以后先说风险")
                .sourceOccurredAt(LocalDateTime.of(2026, 9, 19, 10, 0)).attempts(1).build();
        when(store.claim(anyString(), any(Instant.class), anyInt())).thenReturn(Optional.of(job), Optional.empty());
        when(extraction.process(anyString(), anyString(), anyString(), anyString(), any(LocalDateTime.class)))
                .thenThrow(new IllegalStateException("temporary"));
        when(chatMemory.getSession("session-1")).thenReturn(ChatSession.builder().userId("user-1").build());

        new MemoryJobWorker(store, extraction, chatMemory, config).drain();

        verify(store).retry(eq("job-1"), anyString(), any(Instant.class), any(Instant.class),
                eq("IllegalStateException"), eq(false));
    }

    @Test
    void shouldRetryWhenExtractionReportsARecoverableFailure() {
        MemoryJobStore store = mock(MemoryJobStore.class);
        MemoryExtractionService extraction = mock(MemoryExtractionService.class);
        ChatMemoryService chatMemory = mock(ChatMemoryService.class);
        MemoryConfig config = new MemoryConfig();
        config.getLongTerm().setGenerateEnabled(true);
        MemoryProcessingJob job = MemoryProcessingJob.builder().jobId("job-2").userId("user-1")
                .sessionId("session-1").messageId("message-1").content("以后先说风险")
                .sourceOccurredAt(LocalDateTime.of(2026, 9, 19, 10, 0)).attempts(1).build();
        when(store.claim(anyString(), any(Instant.class), anyInt())).thenReturn(Optional.of(job), Optional.empty());
        when(extraction.process(anyString(), anyString(), anyString(), anyString(), any(LocalDateTime.class)))
                .thenReturn(MemoryExtractionService.Outcome.FAILED);
        when(chatMemory.getSession("session-1")).thenReturn(ChatSession.builder().userId("user-1").build());

        new MemoryJobWorker(store, extraction, chatMemory, config).drain();

        verify(store).retry(eq("job-2"), anyString(), any(Instant.class), any(Instant.class),
                eq("IllegalStateException"), eq(false));
    }

    @Test
    void shouldDiscardClaimedJobsForDeletedSessions() {
        MemoryJobStore store = mock(MemoryJobStore.class);
        MemoryExtractionService extraction = mock(MemoryExtractionService.class);
        ChatMemoryService chatMemory = mock(ChatMemoryService.class);
        MemoryConfig config = new MemoryConfig();
        config.getLongTerm().setGenerateEnabled(true);
        MemoryProcessingJob job = MemoryProcessingJob.builder().jobId("job-3").userId("user-1")
                .sessionId("deleted-session").messageId("message-1").content("以后先说风险").attempts(1).build();
        when(store.claim(anyString(), any(Instant.class), anyInt())).thenReturn(Optional.of(job), Optional.empty());

        new MemoryJobWorker(store, extraction, chatMemory, config).drain();

        verify(store).complete(eq("job-3"), anyString(), eq(MemoryProcessingJob.Status.NO_OUTPUT), any(Instant.class));
        verifyNoInteractions(extraction);
    }
}
