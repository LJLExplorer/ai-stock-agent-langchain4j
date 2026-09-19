package com.ljl.ai.service;

import com.ljl.ai.model.entity.UserLongTermMemory;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

import java.time.LocalDateTime;

/** 自动提取的中间结果，保留来源和处理状态，避免直接把模型/规则输出写入有效记忆。 */
@Data
@Builder
@Document(collection = "user_memory_candidates")
public class UserMemoryCandidate {
    @Id
    private String candidateId;
    @Indexed(unique = true)
    private String idempotencyKey;
    private String userId;
    private String sessionId;
    private String messageId;
    private LocalDateTime sourceOccurredAt;
    private String evidence;
    private String content;
    private UserLongTermMemory.Type type;
    private UserLongTermMemory.Scope scope;
    private String canonicalKey;
    private Status status;
    private String rejectionReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public enum Status {
        PENDING, CONSOLIDATED, NO_OUTPUT, FAILED
    }
}
