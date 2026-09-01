package com.ljl.ai.memory;

import com.ljl.ai.config.MemoryConfig;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

        ShortTermSummaryService service = new ShortTermSummaryService(redis, store, config,
                (oldSummary, messages) -> messages);
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
                (oldSummary, source) -> "summary:" + source);
        service.refresh("user-1:session-1");

        verify(redis, times(3)).opsForValue();
        verify(store).updateMessages("user-1:session-1", messages.subList(2, 4));
    }

    @Test
    void shouldReplacePreviousSummaryWithRecursiveResult() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("old-summary");
        RedisChatMemoryStore store = mock(RedisChatMemoryStore.class);
        MemoryConfig config = new MemoryConfig();
        config.getShortTerm().setMaxChars(1);
        config.getShortTerm().setSummaryTriggerMessages(1);
        config.getShortTerm().setSummaryMaxChars(100);
        when(store.getMessages(anyString())).thenReturn(List.of(
                UserMessage.from("old"), UserMessage.from("new")));

        ShortTermSummaryService service = new ShortTermSummaryService(redis, store, config,
                (oldSummary, source) -> {
                    assertEquals("old-summary", oldSummary);
                    assertTrue(source.contains("old"));
                    return "recursive-summary";
                });
        service.refresh("user-1:session-1");

        verify(values).set("ai:memory:summary:user-1:session-1", "recursive-summary");
    }

    @Test
    void shouldKeepOriginalWindowWhenGeneratedSummaryExceedsConfiguredLimit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisChatMemoryStore store = mock(RedisChatMemoryStore.class);
        MemoryConfig config = new MemoryConfig();
        config.getShortTerm().setMaxChars(1);
        config.getShortTerm().setSummaryTriggerMessages(1);
        config.getShortTerm().setSummaryMaxChars(10);
        when(store.getMessages("user-1:session-1")).thenReturn(List.of(
                UserMessage.from("old"), UserMessage.from("new")));

        ShortTermSummaryService service = new ShortTermSummaryService(redis, store, config,
                (oldSummary, source) -> "summary-that-is-too-long");

        assertThrows(IllegalStateException.class, () -> service.refresh("user-1:session-1"));

        verify(store, never()).updateMessages(any(), anyList());
        verify(values, never()).set(eq("ai:memory:summary:user-1:session-1"), anyString());
    }
}
