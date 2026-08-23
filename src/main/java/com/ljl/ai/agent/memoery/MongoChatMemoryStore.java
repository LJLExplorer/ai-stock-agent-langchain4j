package com.ljl.ai.agent.memoery;

import com.ljl.ai.agent.model.entity.ChatMemoryRecord;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * LangChain4j ChatMemoryStore 的 MongoDB 实现
 * 使用独立的 chat_memory_records 集合存储序列化的消息列表
 * 保留所有消息类型（包括 ToolExecutionResultMessage），避免工具调用无限循环
 */
@Slf4j
@Component
public class MongoChatMemoryStore implements ChatMemoryStore {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        log.debug("从MongoDB获取ChatMemory, sessionId: {}", sessionId);

        ChatMemoryRecord record = mongoTemplate.findById(sessionId, ChatMemoryRecord.class);
        
        if (record == null || record.getMessagesJson() == null || record.getMessagesJson().isEmpty()) {
            log.debug("未找到ChatMemory记录, sessionId: {}", sessionId);
            return Collections.emptyList();
        }

        try {
            List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(record.getMessagesJson());
            log.debug("获取到 {} 条消息, sessionId: {}", messages.size(), sessionId);
            return messages;
        } catch (Exception e) {
            log.error("反序列化ChatMemory失败, sessionId: {}", sessionId, e);
            throw new IllegalStateException("ChatMemory反序列化失败: " + sessionId, e);
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = memoryId.toString();
        log.debug("更新MongoDB ChatMemory, sessionId: {}, 消息数: {}", sessionId, messages.size());

        try {
            // 序列化所有消息（包括工具调用相关消息）
            String messagesJson = ChatMessageSerializer.messagesToJson(messages);
            
            ChatMemoryRecord record = ChatMemoryRecord.builder()
                    .sessionId(sessionId)
                    .messagesJson(messagesJson)
                    .lastUpdateTime(LocalDateTime.now())
                    .build();

            mongoTemplate.save(record);
            log.debug("ChatMemory更新完成, sessionId: {}", sessionId);
        } catch (Exception e) {
            log.error("序列化ChatMemory失败, sessionId: {}", sessionId, e);
            throw new IllegalStateException("ChatMemory持久化失败: " + sessionId, e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        log.info("删除ChatMemory记录, sessionId: {}", sessionId);

        ChatMemoryRecord record = mongoTemplate.findById(sessionId, ChatMemoryRecord.class);
        if (record != null) {
            mongoTemplate.remove(record);
        }
    }
}
