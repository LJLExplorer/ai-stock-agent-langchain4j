package com.ljl.ai.service;

import com.ljl.ai.config.MemoryConfig;
import com.ljl.ai.memory.ChatMemoryService;
import com.ljl.ai.model.entity.ChatSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/** 领取持久化记忆任务；worker 中断后租约到期即可由后续轮次接管。 */
@Slf4j
@Service
public class MemoryJobWorker {
    private final MemoryJobStore jobStore;
    private final MemoryExtractionService extractionService;
    private final ChatMemoryService chatMemoryService;
    private final MemoryConfig config;
    private final String workerId = UUID.randomUUID().toString();

    public MemoryJobWorker(MemoryJobStore jobStore, MemoryExtractionService extractionService,
                           ChatMemoryService chatMemoryService, MemoryConfig config) {
        this.jobStore = jobStore;
        this.extractionService = extractionService;
        this.chatMemoryService = chatMemoryService;
        this.config = config;
    }

    @Async("memoryTaskExecutor")
    public void drainAsync() {
        drain();
    }

    @Scheduled(fixedDelayString = "${memory.long-term.job-poll-delay-ms:30000}")
    public void scheduledDrain() {
        drain();
    }

    void drain() {
        if (!config.getLongTerm().isGenerateEnabled()) {
            return;
        }
        int limit = Math.max(1, config.getLongTerm().getJobBatchSize());
        for (int processed = 0; processed < limit; processed++) {
            var claimed = jobStore.claim(workerId, Instant.now(), Math.max(1, config.getLongTerm().getJobLeaseSeconds()));
            if (claimed.isEmpty()) {
                return;
            }
            process(claimed.get());
        }
    }

    private void process(MemoryProcessingJob job) {
        Instant now = Instant.now();
        try {
            ChatSession session = chatMemoryService.getSession(job.getSessionId());
            if (session == null || !job.getUserId().equals(session.getUserId())) {
                jobStore.complete(job.getJobId(), workerId, MemoryProcessingJob.Status.NO_OUTPUT, now);
                return;
            }
            MemoryExtractionService.Outcome outcome = extractionService.process(job.getUserId(), job.getSessionId(),
                    job.getMessageId(), job.getContent(), job.getSourceOccurredAt());
            if (outcome == MemoryExtractionService.Outcome.FAILED) {
                throw new IllegalStateException("MEMORY_EXTRACTION_FAILED");
            }
            MemoryProcessingJob.Status status = outcome == MemoryExtractionService.Outcome.CONSOLIDATED
                    ? MemoryProcessingJob.Status.SUCCEEDED : MemoryProcessingJob.Status.NO_OUTPUT;
            jobStore.complete(job.getJobId(), workerId, status, now);
        } catch (RuntimeException exception) {
            boolean dead = job.getAttempts() >= config.getLongTerm().getJobMaxAttempts();
            long delaySeconds = Math.min(300, 1L << Math.min(8, Math.max(0, job.getAttempts())));
            jobStore.retry(job.getJobId(), workerId, now, now.plusSeconds(delaySeconds),
                    exception.getClass().getSimpleName(), dead);
            log.warn("memory_job_failed jobId={}, attempts={}, dead={}, errorType={}", job.getJobId(),
                    job.getAttempts(), dead, exception.getClass().getSimpleName());
        }
    }
}
