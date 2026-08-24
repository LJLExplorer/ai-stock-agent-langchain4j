package com.ljl.ai.agent.service;

import com.ljl.ai.agent.memoery.ShortTermSummaryService;
import com.ljl.ai.agent.model.entity.UserLongTermMemory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ChatServiceIntegrationTest {
    @Test
    void shouldBuildPromptContextFromSummaryAndUserLongTermMemory() {
        ChatService chatService = new ChatService();
        ShortTermSummaryService summaryService = mock(ShortTermSummaryService.class);
        LongTermMemoryService longTermMemoryService = mock(LongTermMemoryService.class);
        when(summaryService.get("user-1:session-1")).thenReturn("用户关注新能源");
        when(longTermMemoryService.recall("user-1", "投资偏好"))
                .thenReturn(List.of(UserLongTermMemory.builder().content("偏好长期价值投资").build()));
        ReflectionTestUtils.setField(chatService, "shortTermSummaryService", summaryService);
        ReflectionTestUtils.setField(chatService, "longTermMemoryService", longTermMemoryService);

        String context = chatService.buildMemoryContext("user-1", "session-1", "投资偏好");

        assertTrue(context.contains("用户关注新能源"));
        assertTrue(context.contains("偏好长期价值投资"));
    }
}
