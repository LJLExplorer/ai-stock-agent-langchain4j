package com.ljl.ai.service;

import com.ljl.ai.memory.ChatMemoryService;
import com.ljl.ai.memory.ConversationTopicStore;
import com.ljl.ai.memory.RedisChatMemoryProvider;
import com.ljl.ai.memory.ShortTermSummaryService;
import com.ljl.ai.model.entity.ChatSession;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceMemoryKeyTest {
    @Test
    void shouldBuildUserAndSessionScopedMemoryId() {
        assertEquals("user-1:session-1", ChatService.memoryId("user-1", "session-1"));
    }

    @Test
    void shouldDeleteShortTermSummaryWhenSessionIsDeleted() {
        ChatService service = new ChatService();
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        RedisChatMemoryProvider memoryProvider = mock(RedisChatMemoryProvider.class);
        ShortTermSummaryService summaryService = mock(ShortTermSummaryService.class);
        when(chatMemoryService.getSession("session-1"))
                .thenReturn(ChatSession.builder().sessionId("session-1").userId("user-1").build());
        ReflectionTestUtils.setField(service, "chatMemoryService", chatMemoryService);
        ReflectionTestUtils.setField(service, "chatMemoryProvider", memoryProvider);
        ReflectionTestUtils.setField(service, "shortTermSummaryService", summaryService);

        service.deleteSession("session-1", "user-1");

        verify(memoryProvider).clearMemory("user-1:session-1");
        verify(summaryService).delete("user-1:session-1");
        verify(chatMemoryService).deleteSession("session-1");
    }

    @Test
    void shouldDeleteAllKnownTopicMemoriesWithSession() {
        ChatService service = new ChatService();
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        RedisChatMemoryProvider memoryProvider = mock(RedisChatMemoryProvider.class);
        ShortTermSummaryService summaryService = mock(ShortTermSummaryService.class);
        ConversationTopicStore topicStore = mock(ConversationTopicStore.class);
        when(chatMemoryService.getSession("session-1"))
                .thenReturn(ChatSession.builder().sessionId("session-1").userId("user-1").build());
        when(topicStore.modelMemoryIds("user-1:session-1"))
                .thenReturn(List.of("user-1:session-1", "user-1:session-1:topic:one"));
        ReflectionTestUtils.setField(service, "chatMemoryService", chatMemoryService);
        ReflectionTestUtils.setField(service, "chatMemoryProvider", memoryProvider);
        ReflectionTestUtils.setField(service, "shortTermSummaryService", summaryService);
        ReflectionTestUtils.setField(service, "conversationTopicStore", topicStore);

        service.deleteSession("session-1", "user-1");

        verify(memoryProvider).clearMemory("user-1:session-1");
        verify(memoryProvider).clearMemory("user-1:session-1:topic:one");
        verify(summaryService).delete("user-1:session-1:topic:one");
        verify(topicStore).delete("user-1:session-1");
    }
}
