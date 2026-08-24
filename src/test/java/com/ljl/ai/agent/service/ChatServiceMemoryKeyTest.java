package com.ljl.ai.agent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatServiceMemoryKeyTest {
    @Test
    void shouldBuildUserAndSessionScopedMemoryId() {
        assertEquals("user-1:session-1", ChatService.memoryId("user-1", "session-1"));
    }
}
