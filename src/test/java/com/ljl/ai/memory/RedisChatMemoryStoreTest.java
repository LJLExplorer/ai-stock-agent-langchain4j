package com.ljl.ai.memory;

import com.ljl.ai.config.MemoryConfig;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class RedisChatMemoryStoreTest {
    @Test
    void shouldReadMessagesFromUserAndSessionScopedRedisList() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        when(lists.range("ai:memory:messages:user-1:session-1", 0, -1)).thenReturn(List.of("[]"));

        RedisChatMemoryStore store = new RedisChatMemoryStore(redis, new MemoryConfig());

        List<ChatMessage> messages = store.getMessages("user-1:session-1");

        assertTrue(messages.isEmpty());
        verify(lists).range("ai:memory:messages:user-1:session-1", 0, -1);
        verify(lists, never()).range("ai:memory:messages:user-2:session-1", 0, -1);
    }
}
