package com.ljl.ai.service;

import com.ljl.ai.memory.ChatMemoryService;
import com.ljl.ai.model.entity.ChatSession;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceCreateSessionTest {
    @Test
    void researchRequiresAnExistingActiveSession() {
        ChatMemoryService memory = mock(ChatMemoryService.class);
        ChatServiceFixture fixture = new ChatServiceFixture();
        ChatService service = fixture.service;
        ReflectionTestUtils.setField(fixture.persistence, "chatMemoryService", memory);
        ReflectionTestUtils.setField(fixture.context, "chatMemoryService", memory);
        assertThrows(IllegalArgumentException.class,
                () -> service.requireSessionForExecution("missing", "user-1"));

        ChatSession session = ChatSession.builder().sessionId("session-1").userId("user-1").status("ACTIVE").build();
        when(memory.getSession("session-1")).thenReturn(session);
        assertEquals(session, service.requireSessionForExecution("session-1", "user-1"));
        assertThrows(SecurityException.class, () -> service.requireSessionForExecution("session-1", "user-2"));
        session.setStatus("CLOSED");
        assertThrows(IllegalStateException.class, () -> service.requireSessionForExecution("session-1", "user-1"));
        verify(memory, never()).createSession(any(), any());
    }

    @Test
    void sessionDeletedAfterAcceptanceMustNotCreateAReplacementSession() {
        ChatMemoryService memory = mock(ChatMemoryService.class);
        ChatServiceFixture fixture = new ChatServiceFixture();
        ChatService service = fixture.service;
        ReflectionTestUtils.setField(fixture.persistence, "chatMemoryService", memory);
        ReflectionTestUtils.setField(fixture.context, "chatMemoryService", memory);
        var response = service.chat(com.ljl.ai.model.dto.ChatRequest.builder()
                .userId("user-1").sessionId("deleted").message("分析600519").build(), "accepted-execution");

        assertFalse(response.getSuccess());
        assertEquals("deleted", response.getSessionId());
        verify(memory, never()).getOrCreateSession(any(), any(), any());
        verify(memory, never()).createSession(any(), any());
    }

    @Test
    void shouldCreateSessionImmediatelyForUser() {
        ChatMemoryService memoryService = new ChatMemoryService() {
            @Override
            public ChatSession createSession(String userId, String orderId) {
                assertEquals("demo-user", userId);
                assertEquals("600519", orderId);
                return ChatSession.builder().sessionId("new-session").userId(userId).build();
            }
        };
        ChatServiceFixture fixture = new ChatServiceFixture();
        ChatService service = fixture.service;
        ReflectionTestUtils.setField(fixture.persistence, "chatMemoryService", memoryService);
        ReflectionTestUtils.setField(fixture.context, "chatMemoryService", memoryService);

        ChatSession result = service.createSession(" demo-user ", "600519");

        assertEquals("new-session", result.getSessionId());
    }

    @Test
    void shouldRejectBlankUserId() {
        ChatServiceFixture fixture = new ChatServiceFixture();
        ChatService service = fixture.service;

        assertThrows(IllegalArgumentException.class, () -> service.createSession("  ", null));
    }
}
