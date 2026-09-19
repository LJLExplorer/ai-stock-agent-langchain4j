package com.ljl.ai.service;

import com.ljl.ai.config.MemoryConfig;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;

/** 消息持久化后先入可靠任务队列，再通知 worker；通知失败不影响后续轮询领取任务。 */
@Service
public class MemoryJobService {
    private final MemoryJobStore jobStore;
    private final MemoryJobWorker worker;
    private final MemoryConfig config;

    public MemoryJobService(MemoryJobStore jobStore, MemoryJobWorker worker, MemoryConfig config) {
        this.jobStore = jobStore;
        this.worker = worker;
        this.config = config;
    }

    public void enqueue(String userId, String sessionId, String messageId, String content) {
        enqueue(userId, sessionId, messageId, content, LocalDateTime.now());
    }

    public void enqueue(String userId, String sessionId, String messageId, String content,
                        LocalDateTime sourceOccurredAt) {
        if (!config.getLongTerm().isGenerateEnabled()) {
            return;
        }
        jobStore.enqueue(userId, sessionId, messageId, content, sourceOccurredAt, Instant.now());
        worker.drainAsync();
    }
}
