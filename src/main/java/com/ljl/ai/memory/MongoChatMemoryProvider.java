package com.ljl.ai.memory;

import com.ljl.ai.config.MemoryConfig;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ChatMemory 提供者
 * 使用 MongoDB 持久化的 ChatMemoryStore
 * 消息存储在独立的 chat_memory_records 集合，保留所有消息类型
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MongoChatMemoryProvider implements ChatMemoryProvider {

    private final RedisChatMemoryStore redisChatMemoryStore;
    private final MemoryConfig memoryConfig;

    @Override
    public ChatMemory get(Object memoryId) {
        String scopedMemoryId = memoryId.toString();
        log.debug("获取Redis ChatMemory, memoryId: {}", scopedMemoryId);

        return MessageWindowChatMemory.builder()
                .id(scopedMemoryId)
                .maxMessages(memoryConfig.getShortTerm().getMaxMessages())
                .chatMemoryStore(redisChatMemoryStore)
                .build();
    }

    /**
     * 清除指定会话的 ChatMemory
     */
    public void clearMemory(String memoryId) {
        redisChatMemoryStore.deleteMessages(memoryId);
        log.debug("清除Redis ChatMemory, memoryId: {}", memoryId);
    }
}
