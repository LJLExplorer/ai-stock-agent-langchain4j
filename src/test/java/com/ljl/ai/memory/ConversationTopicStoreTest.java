package com.ljl.ai.memory;

import com.ljl.ai.config.MemoryConfig;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationTopicStoreTest {
    @Test
    void shouldPersistActiveTopicAndCreateIsolatedMemoryId() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        MemoryConfig config = new MemoryConfig();
        config.getShortTerm().setTtl(60);
        ConversationTopicStore store = new ConversationTopicStore(redis, config);

        ConversationTopicStore.TopicState state = store.activate("user:session", " 600519\n");

        assertEquals("600519", state.activeTopicKey());
        assertEquals("user:session", ConversationTopicStore.topicMemoryId("user:session", "general"));
        assertNotEquals("user:session", ConversationTopicStore.topicMemoryId("user:session", "600519"));
        verify(values).set(org.mockito.ArgumentMatchers.eq("ai:memory:topics:user:session"),
                org.mockito.ArgumentMatchers.contains("600519"), org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(60)));
    }
}
