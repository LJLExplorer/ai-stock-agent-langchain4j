package com.ljl.ai.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

/** 持久化队列的最小接口；领取、完成和重试都以 worker 租约为条件更新。 */
public interface MemoryJobStore {
    MemoryProcessingJob enqueue(String userId, String sessionId, String messageId, String content,
                                LocalDateTime sourceOccurredAt, Instant now);

    Optional<MemoryProcessingJob> claim(String workerId, Instant now, int leaseSeconds);

    void complete(String jobId, String workerId, MemoryProcessingJob.Status status, Instant now);

    void retry(String jobId, String workerId, Instant now, Instant nextRunAt, String error, boolean dead);
}
