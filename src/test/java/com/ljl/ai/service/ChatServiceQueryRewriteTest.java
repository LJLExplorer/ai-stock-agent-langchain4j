package com.ljl.ai.service;

import com.ljl.ai.agent.QueryRewriteAssistant;
import com.ljl.ai.memory.ConversationQuery;
import com.ljl.ai.memory.ConversationTopicStore;
import com.ljl.ai.memory.ShortTermSummaryService;
import com.ljl.ai.model.entity.UserLongTermMemory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
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
        when(rewriter.rewrite("它最近怎么样", "", "用户正在关注贵州茅台",
                "当前话题：general\n最近话题："))
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
        verify(rewriter).rewrite("它最近怎么样", "", "用户正在关注贵州茅台",
                "当前话题：general\n最近话题：");
        verify(longTermMemoryService).recall("user-1", retrievalQuery);
    }

    @Test
    void shouldFallbackToOriginalQueryWhenRewriteIsBlankOrFails() {
        ChatService service = new ChatService();
        QueryRewriteAssistant rewriter = mock(QueryRewriteAssistant.class);
        ReflectionTestUtils.setField(service, "queryRewriteAssistant", rewriter);
        when(rewriter.rewrite("原始问题", "", "", "当前话题：general\n最近话题：")).thenReturn("   ");

        assertEquals("原始问题", service.rewriteRetrievalQuery("原始问题", ""));

        when(rewriter.rewrite("另一个问题", "", "摘要", "当前话题：general\n最近话题："))
                .thenThrow(new IllegalStateException("model unavailable"));
        assertEquals("另一个问题", service.rewriteRetrievalQuery("另一个问题", "摘要"));
    }

    @Test
    void shouldResolveRecentConversationAndStructuredTopicMetadata() {
        ChatService service = new ChatService();
        QueryRewriteAssistant rewriter = mock(QueryRewriteAssistant.class);
        ConversationTopicStore.TopicState topicState = new ConversationTopicStore.TopicState(
                "600519", List.of("600519", "300750"));
        when(rewriter.rewrite("那去年呢", "用户：分析贵州茅台现金流", "旧摘要", topicState.promptContext()))
                .thenReturn("{\"standaloneQuery\":\"贵州茅台2025年现金流情况\","
                        + "\"topicKey\":\"600519\",\"topicRelation\":\"CONTINUE\",\"confidence\":0.96}");
        ReflectionTestUtils.setField(service, "queryRewriteAssistant", rewriter);

        ConversationQuery resolved = service.resolveRetrievalQuery(
                "那去年呢", "用户：分析贵州茅台现金流", "旧摘要", topicState);

        assertEquals("贵州茅台2025年现金流情况", resolved.standaloneQuery());
        assertEquals("600519", resolved.topicKey());
        assertEquals(ConversationQuery.TopicRelation.CONTINUE, resolved.topicRelation());
        assertEquals(0.96D, resolved.confidence());
    }

    @Test
    void explicitStockCodeShouldProtectTopicBoundaryWhenRewriteFails() {
        ChatService service = new ChatService();
        QueryRewriteAssistant rewriter = mock(QueryRewriteAssistant.class);
        ConversationTopicStore.TopicState topicState = new ConversationTopicStore.TopicState(
                "600519", List.of("600519"));
        when(rewriter.rewrite(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("model unavailable"));
        ReflectionTestUtils.setField(service, "queryRewriteAssistant", rewriter);

        ConversationQuery resolved = service.resolveRetrievalQuery(
                "改看300750的技术面", "", "", topicState);

        assertEquals("300750", resolved.topicKey());
        assertEquals(ConversationQuery.TopicRelation.SWITCH, resolved.topicRelation());
    }
}
