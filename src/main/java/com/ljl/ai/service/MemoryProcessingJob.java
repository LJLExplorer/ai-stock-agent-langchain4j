package com.ljl.ai.service;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDateTime;

/** 已保存消息到候选记忆的可靠异步任务。 */
@Data
@Builder
@Document(collection = "memory_processing_jobs")
public class MemoryProcessingJob {
    @Id
    private String jobId;
    @Indexed(unique = true)
    private String idempotencyKey;
    private String userId;
    private String sessionId;
    private String messageId;
    private String content;
    /** 用户原文的落盘时间，用于拒绝乱序完成的旧偏好。 */
    private LocalDateTime sourceOccurredAt;
    private Status status;
    private int attempts;
    private Instant nextRunAt;
    private String leaseOwner;
    private Instant leaseUntil;
    private String lastError;
    private Instant createTime;
    private Instant updateTime;

    public enum Status {
        PENDING, RUNNING, SUCCEEDED, NO_OUTPUT, RETRY, DEAD
    }
}
