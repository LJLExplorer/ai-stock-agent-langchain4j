package com.ljl.ai.agent.memoery;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ChatMemory 提供者
 * 使用 MongoDB 持久化的 ChatMemoryStore
 * 消息存储在独立的 chat_memory_records 集合，保留所有消息类型
 */
@Slf4j
@Component
public class MongoChatMemoryProvider implements ChatMemoryProvider {

    @Resource
    private MongoChatMemoryStore mongoChatMemoryStore;

    /**
     * 默认最大消息数
     */
    private static final int DEFAULT_MAX_MESSAGES = 20;

    @Override
    public ChatMemory get(Object memoryId) {
        String sessionId = memoryId.toString();
        log.debug("获取ChatMemory, sessionId: {}", sessionId);

        return MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(DEFAULT_MAX_MESSAGES)
                .chatMemoryStore(mongoChatMemoryStore)
                .build();
    }

    /**
     * 清除指定会话的 ChatMemory
     */
    public void clearMemory(String sessionId) {
        mongoChatMemoryStore.deleteMessages(sessionId);
        log.debug("清除ChatMemory, sessionId: {}", sessionId);
    }
}
