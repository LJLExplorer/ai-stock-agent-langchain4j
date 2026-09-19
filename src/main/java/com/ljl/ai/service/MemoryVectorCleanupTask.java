package com.ljl.ai.service;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** 向量删除失败后的持久补偿任务；不保存记忆正文。 */
@Data
@Builder
@Document(collection = "memory_vector_cleanup_tasks")
public class MemoryVectorCleanupTask {
    @Id
    private String taskId;
    @Indexed(unique = true)
    private String idempotencyKey;
    private String memoryId;
    private String vectorId;
    private long memoryVersion;
    private Status status;
    private int attempts;
    private LocalDateTime nextRunAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public enum Status {
        PENDING, COMPLETED
    }
}
