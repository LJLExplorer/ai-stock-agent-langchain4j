package com.ljl.ai.service;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** 增量扫描游标；createTime + messageId 形成稳定顺序，重启后可从最后确认位置继续。 */
@Data
@Builder
@Document(collection = "memory_scan_cursors")
public class MemoryScanCursor {
    public static final String USER_MESSAGES = "user-memory-v1";

    @Id
    private String cursorId;
    private LocalDateTime lastCreateTime;
    private String lastMessageId;
    private LocalDateTime updateTime;
}
