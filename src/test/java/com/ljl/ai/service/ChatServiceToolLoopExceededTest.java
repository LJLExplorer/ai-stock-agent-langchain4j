package com.ljl.ai.service;

import com.ljl.ai.agent.StockAnalysisAssistant;
import com.ljl.ai.memory.ChatMemoryService;
import com.ljl.ai.memory.RedisChatMemoryProvider;
import com.ljl.ai.memory.ShortTermSummaryService;
import com.ljl.ai.model.dto.ChatRequest;
import com.ljl.ai.model.dto.ChatResponse;
import com.ljl.ai.model.entity.ChatSession;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 LangChain4j 工具调用循环超出上限时的降级行为：
 * 清理该会话的模型记忆，并返回用户可理解的提示，而不是把内部异常原样抛出。
 */
class ChatServiceToolLoopExceededTest {

    @ParameterizedTest
    @CsvSource({"true,false", "true,true", "false,false", "false,true"})
    void shouldReturnControlledFailureEvenWhenMemoryCleanupFails(boolean toolLoopExceeded, boolean cleanupFails) {
        ChatServiceFixture fixture = new ChatServiceFixture();
        ChatService chatService = fixture.service;

        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        RedisChatMemoryProvider chatMemoryProvider = mock(RedisChatMemoryProvider.class);
        StockAnalysisAssistant assistant = mock(StockAnalysisAssistant.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        ShortTermSummaryService shortTermSummaryService = mock(ShortTermSummaryService.class);
        LongTermMemoryService longTermMemoryService = mock(LongTermMemoryService.class);

        when(chatMemoryService.getOrCreateSession(any(), any(), any()))
                .thenReturn(ChatSession.builder().sessionId("session-1").build());
        when(chatMemoryProvider.get(any())).thenReturn(chatMemory);
        when(chatMemory.messages()).thenReturn(Collections.emptyList());
        when(longTermMemoryService.recall(any(), any())).thenReturn(Collections.emptyList());
        when(assistant.chatWithMemory(any(), any(), any())).thenThrow(
                new RuntimeException(toolLoopExceeded
                        ? "Something is wrong, exceeded 10 sequential tool executions"
                        : "url error: private upstream details"));
        if (cleanupFails) {
            doThrow(new IllegalStateException("Redis unavailable: private connection details"))
                    .when(chatMemoryProvider).clearMemory("user-1:session-1");
        }

        ReflectionTestUtils.setField(fixture.persistence, "chatMemoryService", chatMemoryService);
        ReflectionTestUtils.setField(fixture.context, "chatMemoryService", chatMemoryService);
        ReflectionTestUtils.setField(fixture.assembler, "chatMemoryProvider", chatMemoryProvider);
        ReflectionTestUtils.setField(fixture.persistence, "chatMemoryProvider", chatMemoryProvider);
        ReflectionTestUtils.setField(fixture.failure, "chatMemoryProvider", chatMemoryProvider);
        ReflectionTestUtils.setField(fixture.context, "shortTermSummaryService", shortTermSummaryService);
        ReflectionTestUtils.setField(fixture.persistence, "shortTermSummaryService", shortTermSummaryService);
        ReflectionTestUtils.setField(fixture.context, "longTermMemoryService", longTermMemoryService);
        ReflectionTestUtils.setField(fixture.execution, "stockAnalysisAssistantWithoutTools", assistant);

        ChatRequest request = ChatRequest.builder()
                .userId("user-1")
                .message("反复调用工具的问题")
                .build();

        ChatResponse response = chatService.chat(request);

        assertFalse(response.getSuccess());
        assertEquals("session-1", response.getSessionId());
        assertNotNull(response.getMessageId());
        assertNotNull(response.getResponseTime());
        assertEquals("对话处理失败，请稍后重试", response.getErrorMessage());
        assertEquals(toolLoopExceeded && !cleanupFails, response.getContent().contains("已重置"));
        if (toolLoopExceeded && cleanupFails) {
            assertTrue(response.getContent().contains("新建会话"));
        }
        assertFalse(response.getContent().contains("private"));
        verify(chatMemoryProvider).clearMemory("user-1:session-1");
    }
}
