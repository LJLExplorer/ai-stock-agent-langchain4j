package com.ljl.ai.service;

import com.ljl.ai.agent.StockAnalysisAssistant;
import com.ljl.ai.memory.ChatMemoryService;
import com.ljl.ai.memory.RedisChatMemoryProvider;
import com.ljl.ai.memory.ShortTermSummaryService;
import com.ljl.ai.model.dto.ChatRequest;
import com.ljl.ai.model.entity.ChatSession;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceMemoryContextTest {

    @Test
    void shouldPassSummaryAsSystemContextWithoutChangingCurrentUserMessage() {
        ChatService service = new ChatService();
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        RedisChatMemoryProvider chatMemoryProvider = mock(RedisChatMemoryProvider.class);
        ShortTermSummaryService summaryService = mock(ShortTermSummaryService.class);
        LongTermMemoryService longTermMemoryService = mock(LongTermMemoryService.class);
        StockAnalysisAssistant assistant = mock(StockAnalysisAssistant.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        when(chatMemoryService.getOrCreateSession(any(), any(), any()))
                .thenReturn(ChatSession.builder().sessionId("session-1").build());
        when(chatMemoryProvider.get(any())).thenReturn(chatMemory);
        when(chatMemory.messages()).thenReturn(Collections.emptyList());
        when(summaryService.get("user-1:session-1")).thenReturn("此前讨论贵州茅台的估值风险");
        when(longTermMemoryService.recall(any(), any())).thenReturn(Collections.emptyList());
        when(assistant.chatWithMemory(any(), any(), any())).thenReturn("回答");

        ReflectionTestUtils.setField(service, "chatMemoryService", chatMemoryService);
        ReflectionTestUtils.setField(service, "chatMemoryProvider", chatMemoryProvider);
        ReflectionTestUtils.setField(service, "shortTermSummaryService", summaryService);
        ReflectionTestUtils.setField(service, "longTermMemoryService", longTermMemoryService);
        ReflectionTestUtils.setField(service, "stockAnalysisAssistantWithoutTools", assistant);

        service.chat(ChatRequest.builder().userId("user-1").message("风险再展开说说").build());

        verify(assistant).chatWithMemory(eq("user-1:session-1"), eq("风险再展开说说"),
                contains("此前讨论贵州茅台的估值风险"));
    }
}
