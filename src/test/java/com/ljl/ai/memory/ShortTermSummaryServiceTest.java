package com.ljl.ai.memory;

import com.ljl.ai.config.MemoryConfig;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ShortTermSummaryServiceTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void compactionMustKeepParallelToolCallsTogetherWithTheirResults(int trailingMessages) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisChatMemoryStore store = mock(RedisChatMemoryStore.class);
        MemoryConfig config = new MemoryConfig();
        config.getShortTerm().setMaxChars(1);
        ToolExecutionRequest quote = ToolExecutionRequest.builder().id("quote-1").name("quote").arguments("{}").build();
        ToolExecutionRequest news = ToolExecutionRequest.builder().id("news-1").name("news").arguments("{}").build();
        List<ChatMessage> original = new ArrayList<>(List.of(
                SystemMessage.from("system"), UserMessage.from("question"), AiMessage.from(quote, news),
                ToolExecutionResultMessage.from(quote, "price"), ToolExecutionResultMessage.from(news, "news"),
                AiMessage.from("answer")));
        if (trailingMessages > 0) {
            original.add(UserMessage.from("another question"));
            original.add(AiMessage.from("another answer"));
        }
        AtomicReference<List<ChatMessage>> window = new AtomicReference<>(original);
        when(store.getMessages("memory")).thenAnswer(ignored -> new ArrayList<>(window.get()));
        when(store.compact(eq("memory"), anyList(), anyInt(), isNull(), eq("summary")))
                .thenAnswer(invocation -> {
                    List<ChatMessage> expected = invocation.getArgument(1);
                    int split = invocation.getArgument(2);
                    window.set(new ArrayList<>(expected.subList(split, expected.size())));
                    return true;
                });
        doAnswer(invocation -> {
            window.set(new ArrayList<>(invocation.<List<ChatMessage>>getArgument(1)));
            return null;
        }).when(store).updateMessages(eq("memory"), anyList());

        new ShortTermSummaryService(redis, store, config, (previous, source) -> "summary").refresh("memory");
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder().id("memory")
                .maxMessages(20).chatMemoryStore(store).build();
        memory.add(SystemMessage.from("updated system"));
        memory.add(UserMessage.from("follow-up"));
        Set<String> callIds = new HashSet<>();
        Set<String> resultIds = new HashSet<>();
        for (ChatMessage message : memory.messages()) {
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                ai.toolExecutionRequests().forEach(call -> callIds.add(call.id()));
            }
            if (message instanceof ToolExecutionResultMessage result) {
                assertTrue(callIds.contains(result.id()), "Next request contains an orphan tool result: " + result.id());
                resultIds.add(result.id());
            }
        }
        assertEquals(Set.of("quote-1", "news-1"), resultIds);
    }

    @Test
    void shouldSkipCompactionWhenOnlyOneToolExchangeRemains() {
        RedisChatMemoryStore store = mock(RedisChatMemoryStore.class);
        MemoryConfig config = new MemoryConfig();
        config.getShortTerm().setMaxChars(1);
        ToolExecutionRequest call = ToolExecutionRequest.builder().id("call-1").name("quote").arguments("{}").build();
        when(store.getMessages("memory")).thenReturn(List.of(
                AiMessage.from(call), ToolExecutionResultMessage.from(call, "price")));
        new ShortTermSummaryService(null, store, config, (previous, source) -> {
            throw new AssertionError("There is no safe compaction boundary");
        }).refresh("memory");
        verify(store, never()).compact(anyString(), anyList(), anyInt(), any(), anyString());
    }

    @Test
    void shouldNotSummarizeWhenCharacterLimitHasNotBeenReached() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisChatMemoryStore store = mock(RedisChatMemoryStore.class);
        MemoryConfig config = new MemoryConfig();
        config.getShortTerm().setMaxChars(100);
        config.getShortTerm().setSummaryTriggerMessages(2);
        when(store.getMessages("user-1:session-1")).thenReturn(List.of(UserMessage.from("short")));

        ShortTermSummaryService service = new ShortTermSummaryService(redis, store, config,
                (oldSummary, messages) -> messages);
        service.refresh("user-1:session-1");

        verifyNoInteractions(redis);
        verify(store, never()).updateMessages(any(), anyList());
    }

    @Test
    void shouldSummarizeWhenMessageLimitIsReachedBeforeCharacterLimit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisChatMemoryStore store = mock(RedisChatMemoryStore.class);
        MemoryConfig config = new MemoryConfig();
        config.getShortTerm().setMaxChars(10_000);
        config.getShortTerm().setSummaryTriggerMessages(2);
        List<ChatMessage> messages = List.of(UserMessage.from("old"), UserMessage.from("new"));
        when(store.getMessages("user-1:session-1")).thenReturn(messages);

        ShortTermSummaryService service = new ShortTermSummaryService(redis, store, config,
                (oldSummary, source) -> "summary");
        service.refresh("user-1:session-1");

        verify(store).compact("user-1:session-1", messages, 1, null, "summary");
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

        verify(redis).opsForValue();
        verify(store).compact(eq("user-1:session-1"), eq(messages), eq(2), isNull(), startsWith("summary:"));
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

        verify(store).compact(eq("user-1:session-1"), anyList(), eq(1), eq("old-summary"), eq("recursive-summary"));
    }

    @Test
    void failedAtomicCommitMustNotRestoreAnOldWindowOverConcurrentMessages() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisChatMemoryStore store = mock(RedisChatMemoryStore.class);
        MemoryConfig config = new MemoryConfig();
        config.getShortTerm().setSummaryTriggerMessages(2);
        when(store.getMessages("memory")).thenReturn(List.of(UserMessage.from("old"), UserMessage.from("new")));
        when(store.compact(eq("memory"), anyList(), eq(1), isNull(), eq("summary")))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        ShortTermSummaryService service = new ShortTermSummaryService(redis, store, config, (old, source) -> "summary");
        assertThrows(IllegalStateException.class, () -> service.refresh("memory"));
        verify(store, never()).updateMessages(any(), anyList());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
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
        verify(values, never()).set(eq("ai:memory:summary:user-1:session-1"), anyString(), any(Duration.class));
    }
}
