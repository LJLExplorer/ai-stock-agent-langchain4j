package com.ljl.ai.service;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** 每个用户偏好维度的原子发布闸门；正文仍保存在审计记忆记录中。 */
@Data
@Builder
@Document(collection = "memory_preference_slots")
public class MemoryPreferenceSlot {
    @Id
    private String slotId;
    private LocalDateTime latestSourceOccurredAt;
    private long version;
    private LocalDateTime updateTime;
}
