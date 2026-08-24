package com.ljl.ai.agent.service;

import com.ljl.ai.agent.agent.StockAnalysisAssistant;
import com.ljl.ai.agent.memoery.ChatMemoryService;
import com.ljl.ai.agent.memoery.MongoChatMemoryProvider;
import com.ljl.ai.agent.memoery.ShortTermSummaryService;
import com.ljl.ai.agent.model.dto.ChatRequest;
import com.ljl.ai.agent.model.dto.ChatResponse;
import com.ljl.ai.agent.model.entity.ChatSession;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 LangChain4j 工具调用循环超出上限时的降级行为：
 * 清理该会话的模型记忆，并返回用户可理解的提示，而不是把内部异常原样抛出。
 */
class ChatServiceToolLoopExceededTest {

    @Test
    void shouldClearMemoryAndReturnFriendlyMessageWhenToolLoopExceedsLimit() {
        ChatService chatService = new ChatService();

        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        MongoChatMemoryProvider chatMemoryProvider = mock(MongoChatMemoryProvider.class);
        StockAnalysisAssistant assistant = mock(StockAnalysisAssistant.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        ShortTermSummaryService shortTermSummaryService = mock(ShortTermSummaryService.class);
        LongTermMemoryService longTermMemoryService = mock(LongTermMemoryService.class);

        when(chatMemoryService.getOrCreateSession(any(), any(), any()))
                .thenReturn(ChatSession.builder().sessionId("session-1").build());
        when(chatMemoryProvider.get(any())).thenReturn(chatMemory);
        when(chatMemory.messages()).thenReturn(Collections.emptyList());
        when(longTermMemoryService.recall(any(), any())).thenReturn(Collections.emptyList());
        when(assistant.chat(any(), any())).thenThrow(
                new RuntimeException("Something is wrong, exceeded 10 sequential tool executions"));

        ReflectionTestUtils.setField(chatService, "chatMemoryService", chatMemoryService);
        ReflectionTestUtils.setField(chatService, "chatMemoryProvider", chatMemoryProvider);
        ReflectionTestUtils.setField(chatService, "shortTermSummaryService", shortTermSummaryService);
        ReflectionTestUtils.setField(chatService, "longTermMemoryService", longTermMemoryService);
        ReflectionTestUtils.setField(chatService, "stockAnalysisAssistantWithoutTools", assistant);

        ChatRequest request = ChatRequest.builder()
                .userId("user-1")
                .message("反复调用工具的问题")
                .build();

        ChatResponse response = chatService.chat(request);

        assertFalse(response.getSuccess());
        assertTrue(response.getContent().contains("重置"));
        verify(chatMemoryProvider).clearMemory("user-1:session-1");
    }
}
