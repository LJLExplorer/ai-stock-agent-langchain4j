package com.ljl.ai.agent.service;

import com.ljl.ai.agent.memoery.ChatMemoryService;
import com.ljl.ai.agent.model.entity.ChatSession;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatServiceCreateSessionTest {
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
        ChatService service = new ChatService();
        ReflectionTestUtils.setField(service, "chatMemoryService", memoryService);

        ChatSession result = service.createSession(" demo-user ", "600519");

        assertEquals("new-session", result.getSessionId());
    }

    @Test
    void shouldRejectBlankUserId() {
        ChatService service = new ChatService();

        assertThrows(IllegalArgumentException.class, () -> service.createSession("  ", null));
    }
}
