package com.ljl.ai.agent.memoery;

import com.ljl.ai.agent.config.MemoryConfig;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ShortTermSummaryServiceTest {
    @Test
    void shouldNotSummarizeWhenCharacterLimitHasNotBeenReached() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisChatMemoryStore store = mock(RedisChatMemoryStore.class);
        MemoryConfig config = new MemoryConfig();
        config.getShortTerm().setMaxChars(100);
        config.getShortTerm().setSummaryTriggerMessages(1);
        when(store.getMessages("user-1:session-1")).thenReturn(List.of(UserMessage.from("short")));

        ShortTermSummaryService service = new ShortTermSummaryService(redis, store, config, text -> text);
        service.refresh("user-1:session-1");

        verifyNoInteractions(redis);
        verify(store, never()).updateMessages(any(), anyList());
    }

    @Test
    void shouldKeepLatestHalfAfterSummarizingAnOversizedWindow() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisChatMemoryStore store = mock(RedisChatMemoryStore.class);
        MemoryConfig config = new MemoryConfig();
        config.getShortTerm().setMaxChars(10);
        config.getShortTerm().setSummaryTriggerMessages(1);
        List<ChatMessage> messages = List.of(
                UserMessage.from("old-1"), UserMessage.from("old-2"),
                UserMessage.from("new-1"), UserMessage.from("new-2"));
        when(store.getMessages("user-1:session-1")).thenReturn(messages);

        ShortTermSummaryService service = new ShortTermSummaryService(redis, store, config,
                text -> "summary:" + text);
        service.refresh("user-1:session-1");

        verify(redis, times(3)).opsForValue();
        verify(store).updateMessages("user-1:session-1", messages.subList(2, 4));
    }
}
