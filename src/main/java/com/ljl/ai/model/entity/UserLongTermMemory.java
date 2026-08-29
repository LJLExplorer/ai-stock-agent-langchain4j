package com.ljl.ai.model.entity;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@Document(collection = "user_long_term_memories")
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
}
