package com.ljl.ai.agent.memoery;

import com.ljl.ai.agent.config.MemoryConfig;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {
    private static final String PREFIX = "ai:memory:messages:";

    private final StringRedisTemplate redis;
    private final MemoryConfig memoryConfig;

    private String key(Object memoryId) {
        return PREFIX + memoryId;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        List<String> values = redis.opsForList().range(key(memoryId), 0, -1);
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            List<ChatMessage> messages = new ArrayList<>();
            for (String value : values) {
                messages.addAll(ChatMessageDeserializer.messagesFromJson(value));
            }
            return messages;
        } catch (Exception e) {
            log.error("Redis ChatMemory反序列化失败, memoryId: {}", memoryId, e);
            throw new IllegalStateException("Redis ChatMemory反序列化失败", e);
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String redisKey = key(memoryId);
        redis.execute(new SessionCallback<List<Object>>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> List<Object> execute(org.springframework.data.redis.core.RedisOperations<K, V> rawOperations) {
                org.springframework.data.redis.core.RedisOperations<String, String> operations =
                        (org.springframework.data.redis.core.RedisOperations<String, String>) rawOperations;
                operations.multi();
                operations.delete(redisKey);
                if (messages != null) {
                    for (ChatMessage message : messages) {
                        operations.opsForList().rightPush(redisKey,
                                ChatMessageSerializer.messagesToJson(List.of(message)));
                    }
                }
                operations.expire(redisKey, Duration.ofSeconds(memoryConfig.getShortTerm().getTtl()));
                return operations.exec();
            }
        });
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redis.delete(key(memoryId));
    }
}
