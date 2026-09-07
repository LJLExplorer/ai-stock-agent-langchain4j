package com.ljl.ai.memory;

import org.springframework.data.redis.core.script.DefaultRedisScript;

import com.ljl.ai.config.MemoryConfig;
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
    private static final DefaultRedisScript<Long> COMPACT_SCRIPT =
            new DefaultRedisScript<>("""
                    local count = tonumber(ARGV[1])
                    local split = tonumber(ARGV[2])
                    local ttl = tonumber(ARGV[3])
                    if not ttl or ttl <= 0 or split <= 0 or split >= count then return 0 end
                    local current = redis.call('LRANGE', KEYS[1], 0, -1)
                    if #current ~= count then return 0 end
                    for i = 1, count do
                        if current[i] ~= ARGV[6 + i] then return 0 end
                    end
                    local previous = redis.call('GET', KEYS[2])
                    if (previous or '') ~= ARGV[4] then return 0 end
                    redis.call('LTRIM', KEYS[1], split, -1)
                    redis.call('EXPIRE', KEYS[1], ttl)
                    redis.call('SET', KEYS[2], ARGV[5], 'EX', ttl)
                    redis.call('SET', KEYS[3], ARGV[6], 'EX', ttl)
                    return 1
                    """, Long.class);

    private final StringRedisTemplate redis;
    private final MemoryConfig memoryConfig;

    private String key(Object memoryId) {
        return PREFIX + memoryId;
    }

    /** 按存储顺序还原模型消息；损坏的数据抛出异常，避免静默丢失历史后继续对话。 */
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

    /** 使用 Redis 事务整体替换消息窗口并刷新 TTL，使读者不会看到删除与逐条写入之间的中间状态。 */
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

    /** 生成摘要期间窗口或旧摘要有变化则放弃本次压缩，不覆盖并发写入。 */
    public boolean compact(String memoryId, List<ChatMessage> expectedMessages, int split,
                           String previousSummary, String summary) {
        long ttl = memoryConfig.getShortTerm().getTtl();
        if (ttl <= 0) {
            throw new IllegalArgumentException("短期记忆 TTL 必须大于零");
        }
        List<String> arguments = new ArrayList<>();
        arguments.add(Integer.toString(expectedMessages.size()));
        arguments.add(Integer.toString(split));
        arguments.add(Long.toString(ttl));
        arguments.add(previousSummary == null ? "" : previousSummary);
        arguments.add(summary);
        arguments.add(Integer.toString(split));
        expectedMessages.forEach(message -> arguments.add(ChatMessageSerializer.messagesToJson(List.of(message))));
        Long result = redis.execute(COMPACT_SCRIPT,
                List.of(key(memoryId), "ai:memory:summary:" + memoryId, "ai:memory:summary-index:" + memoryId),
                arguments.toArray());
        return Long.valueOf(1).equals(result);
    }
}
