package com.ljl.ai.service;

import com.ljl.ai.memoery.ChatMemoryService;
import com.ljl.ai.memoery.MongoChatMemoryProvider;
import com.ljl.ai.memoery.ShortTermSummaryService;
import com.ljl.ai.model.entity.ChatSession;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
        MongoChatMemoryProvider memoryProvider = mock(MongoChatMemoryProvider.class);
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
}
