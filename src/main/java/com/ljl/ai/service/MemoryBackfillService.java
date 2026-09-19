package com.ljl.ai.service;

import com.ljl.ai.config.MemoryConfig;
import com.ljl.ai.memory.ChatMemoryService;
import com.ljl.ai.model.entity.ChatMessage;
import com.ljl.ai.model.entity.ChatSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 只扫描已经持久化的用户消息，为消息落盘与任务入队之间的故障提供恢复路径。 */
@Slf4j
@Service
public class MemoryBackfillService {
    private final MongoTemplate mongoTemplate;
    private final ChatMemoryService chatMemoryService;
    private final MemoryJobService memoryJobService;
    private final MemoryConfig config;

    public MemoryBackfillService(MongoTemplate mongoTemplate, ChatMemoryService chatMemoryService,
                                 MemoryJobService memoryJobService, MemoryConfig config) {
        this.mongoTemplate = mongoTemplate;
        this.chatMemoryService = chatMemoryService;
        this.memoryJobService = memoryJobService;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${memory.long-term.backfill-poll-delay-ms:60000}")
    public void scheduledScan() {
        scanOnce();
    }

    void scanOnce() {
        if (!config.getLongTerm().isGenerateEnabled()) {
            return;
        }
        MemoryScanCursor cursor = mongoTemplate.findById(MemoryScanCursor.USER_MESSAGES, MemoryScanCursor.class);
        Query query = userMessagesAfter(cursor).with(Sort.by(Sort.Direction.ASC, "createTime", "messageId"))
                .limit(Math.max(1, config.getLongTerm().getBackfillBatchSize()));
        List<ChatMessage> messages = mongoTemplate.find(query, ChatMessage.class);
        for (ChatMessage message : messages) {
            try {
                ChatSession session = chatMemoryService.getSession(message.getSessionId());
                if (session != null && session.getUserId() != null) {
                    memoryJobService.enqueue(session.getUserId(), message.getSessionId(), message.getMessageId(),
                            message.getContent(), message.getCreateTime());
                }
                cursor = MemoryScanCursor.builder().cursorId(MemoryScanCursor.USER_MESSAGES)
                        .lastCreateTime(message.getCreateTime()).lastMessageId(message.getMessageId())
                        .updateTime(LocalDateTime.now()).build();
                mongoTemplate.save(cursor);
            } catch (RuntimeException exception) {
                log.warn("memory_backfill_enqueue_failed messageId={}, errorType={}", message.getMessageId(),
                        exception.getClass().getSimpleName());
                return;
            }
        }
    }

    private Query userMessagesAfter(MemoryScanCursor cursor) {
        Criteria userRole = Criteria.where("role").is("USER");
        if (cursor == null || cursor.getLastCreateTime() == null) {
            return Query.query(userRole);
        }
        Criteria after = new Criteria().orOperator(
                Criteria.where("createTime").gt(cursor.getLastCreateTime()),
                Criteria.where("createTime").is(cursor.getLastCreateTime())
                        .and("messageId").gt(cursor.getLastMessageId()));
        return Query.query(new Criteria().andOperator(userRole, after));
    }
}
