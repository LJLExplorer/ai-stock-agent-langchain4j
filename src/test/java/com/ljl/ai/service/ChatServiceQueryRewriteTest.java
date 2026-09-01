package com.ljl.ai.service;

import com.ljl.ai.agent.QueryRewriteAssistant;
import com.ljl.ai.memory.ShortTermSummaryService;
import com.ljl.ai.model.entity.UserLongTermMemory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceQueryRewriteTest {

    @Test
    void shouldRewriteQueryWithShortTermSummaryAndUseItForLongTermRecall() {
        ChatService service = new ChatService();
        QueryRewriteAssistant rewriter = mock(QueryRewriteAssistant.class);
        ShortTermSummaryService summaryService = mock(ShortTermSummaryService.class);
        LongTermMemoryService longTermMemoryService = mock(LongTermMemoryService.class);
        when(rewriter.rewrite("它最近怎么样", "用户正在关注贵州茅台"))
                .thenReturn("贵州茅台最近的走势和基本面怎么样");
        when(longTermMemoryService.recall("user-1", "贵州茅台最近的走势和基本面怎么样"))
                .thenReturn(List.of(UserLongTermMemory.builder().content("偏好长期投资").build()));
        ReflectionTestUtils.setField(service, "queryRewriteAssistant", rewriter);
        ReflectionTestUtils.setField(service, "shortTermSummaryService", summaryService);
        ReflectionTestUtils.setField(service, "longTermMemoryService", longTermMemoryService);

        String retrievalQuery = service.rewriteRetrievalQuery("它最近怎么样", "用户正在关注贵州茅台");
        String context = service.buildMemoryContext("user-1", "session-1", retrievalQuery);

        assertEquals("贵州茅台最近的走势和基本面怎么样", retrievalQuery);
        assertEquals("【用户长期记忆】\n- 偏好长期投资", context);
        verify(rewriter).rewrite("它最近怎么样", "用户正在关注贵州茅台");
        verify(longTermMemoryService).recall("user-1", retrievalQuery);
    }

    @Test
    void shouldFallbackToOriginalQueryWhenRewriteIsBlankOrFails() {
        ChatService service = new ChatService();
        QueryRewriteAssistant rewriter = mock(QueryRewriteAssistant.class);
        ReflectionTestUtils.setField(service, "queryRewriteAssistant", rewriter);
        when(rewriter.rewrite("原始问题", "")).thenReturn("   ");

        assertEquals("原始问题", service.rewriteRetrievalQuery("原始问题", ""));

        when(rewriter.rewrite("另一个问题", "摘要")).thenThrow(new IllegalStateException("model unavailable"));
        assertEquals("另一个问题", service.rewriteRetrievalQuery("另一个问题", "摘要"));
    }
}
