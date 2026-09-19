package com.ljl.ai.model.entity;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@Document(collection = "user_long_term_memories")
@CompoundIndex(name = "active_user_preference", unique = true,
        def = "{'userId': 1, 'memoryScope': 1, 'scopeKey': 1, 'canonicalKey': 1}",
        partialFilter = "{'status': 'ACTIVE', 'canonicalKey': {$exists: true}}")
public class UserLongTermMemory {
    @Id
    private String memoryId;
    private String userId;
    private String content;
    private List<String> tags;
    private String vectorId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Boolean enabled;
    /** LEGACY 兼容历史自由文本；其余类型用于受约束的用户偏好。 */
    private Type memoryType;
    private Scope memoryScope;
    private String scopeKey;
    private String canonicalKey;
    private String canonicalValue;
    private Origin origin;
    private Status status;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private LocalDateTime recordedAt;
    private Long version;
    private String supersedes;
    private List<SourceRef> sourceRefs;

    public enum Type {
        RESPONSE_PREFERENCE, ANALYSIS_PREFERENCE, EXPLICIT_CONSTRAINT, LEGACY
    }

    public enum Scope {
        USER, SESSION, TOPIC
    }

    public enum Origin {
        USER_EXPLICIT, AUTO_EXTRACTED, LEGACY
    }

    public enum Status {
        ACTIVE, SUPERSEDED, EXPIRED, DELETED
    }

    @Data
    @Builder
    public static class SourceRef {
        private String sessionId;
        private String messageId;
        private String evidence;
        private LocalDateTime occurredAt;
    }
}
