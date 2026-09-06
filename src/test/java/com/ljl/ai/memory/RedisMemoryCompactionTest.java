package com.ljl.ai.memory;

import com.ljl.ai.config.MemoryConfig;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 仅连接显式指定的本地测试 Redis；只创建/清理本测试 UUID 对应的键。 */
@EnabledIfSystemProperty(named = "review.redis.port", matches = "\\d+")
class RedisMemoryCompactionTest {
    @Test
    void scriptCommitsAllKeysAndRejectsStaleSnapshotsAndWrongTypesBeforeWriting() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1",
                Integer.parseInt(System.getProperty("review.redis.port")));
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        String id = "review-" + UUID.randomUUID();
        String summaryKey = "ai:memory:summary:" + id;
        String indexKey = "ai:memory:summary-index:" + id;
        String messagesKey = "ai:memory:messages:" + id;
        List<ChatMessage> messages = List.of(UserMessage.from("old"), UserMessage.from("new"));
        RedisChatMemoryStore store = new RedisChatMemoryStore(redis, new MemoryConfig());
        try {
            store.updateMessages(id, messages);
            assertTrue(store.compact(id, messages, 1, null, "summary"));
            assertEquals(messages.subList(1, 2), store.getMessages(id));
            assertEquals("summary", redis.opsForValue().get(summaryKey));
            assertEquals("1", redis.opsForValue().get(indexKey));
            assertTrue(redis.getExpire(messagesKey) > 0);
            assertFalse(store.compact(id, messages, 1, null, "stale summary"));
            assertEquals("summary", redis.opsForValue().get(summaryKey));

            store.updateMessages(id, messages);
            assertFalse(store.compact(id, messages, 1, "wrong previous summary", "stale summary"));
            assertEquals(messages, store.getMessages(id));
            redis.delete(summaryKey);
            redis.opsForList().rightPush(summaryKey, "wrong type");
            assertThrows(RuntimeException.class, () -> store.compact(id, messages, 1, null, "replacement"));
            assertEquals(messages, store.getMessages(id));
            assertEquals("1", redis.opsForValue().get(indexKey));
        } finally {
            redis.delete(List.of(summaryKey, indexKey, messagesKey));
            factory.destroy();
        }
    }
}
