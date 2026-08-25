package com.ljl.ai.agent.service;

import com.ljl.ai.agent.agent.AgentPlannerAssistant;
import com.ljl.ai.agent.model.entity.KnowledgeSource;
import com.ljl.ai.agent.model.entity.ToolInvocation;
import com.ljl.ai.agent.planner.PlanValidator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

class ChatServicePlannerTest {

    @Test
    void shouldValidatePlannerJsonBeforeExecution() {
        ChatService chatService = new ChatService();
        AgentPlannerAssistant planner = mock(AgentPlannerAssistant.class);
        when(planner.plan("分析贵州茅台最近为什么跌"))
                .thenReturn("{\"intent\":\"STOCK_ANALYSIS\",\"symbol\":\"600519\",\"tasks\":[\"MARKET_DATA\",\"NEWS_ANALYSIS\"]}");
        ReflectionTestUtils.setField(chatService, "agentPlannerAssistant", planner);

        PlanValidator.ValidatedPlan result = chatService.planForExecution("分析贵州茅台最近为什么跌").orElseThrow();

        assertEquals("600519.SH", result.plan().getSymbol());
        assertEquals(2, result.toolNames().size());
    }

    @Test
    void shouldSafelyFallbackWhenPlannerReturnsInvalidJsonOrIllegalPlan() {
        ChatService chatService = new ChatService();
        AgentPlannerAssistant planner = mock(AgentPlannerAssistant.class);
        when(planner.plan("非法计划")).thenReturn("{not-json}");
        when(planner.plan("越界任务")).thenReturn("{\"intent\":\"STOCK_ANALYSIS\",\"symbol\":\"600519\",\"tasks\":[\"PORTFOLIO_ANALYSIS\"]}");
        ReflectionTestUtils.setField(chatService, "agentPlannerAssistant", planner);

        assertTrue(chatService.planForExecution("非法计划").isEmpty());
        assertTrue(chatService.planForExecution("越界任务").isEmpty());
    }

    @Test
    void shouldExtractPlanJsonWhenPlannerAddsDisclaimerAroundIt() {
        ChatService chatService = new ChatService();
        AgentPlannerAssistant planner = mock(AgentPlannerAssistant.class);
        when(planner.plan("带免责声明的计划")).thenReturn(
                "⚠️ 温馨提示：股市数据瞬息万变，以上信息仅供学习参考，不构成任何投资建议。\n"
                        + "{\"intent\":\"STOCK_ANALYSIS\",\"symbol\":\"600519\","
                        + "\"tasks\":[\"MARKET_DATA\"]}");
        ReflectionTestUtils.setField(chatService, "agentPlannerAssistant", planner);

        assertTrue(chatService.planForExecution("带免责声明的计划").isPresent());
    }

    @Test
    void shouldInferRestrictedPlanWhenPlannerReturnsMarkdownAnalysis() {
        ChatService chatService = new ChatService();
        AgentPlannerAssistant planner = mock(AgentPlannerAssistant.class);
        when(planner.plan("请查询600511实时行情并分析相关新闻是否适合买入"))
                .thenReturn("### 标的确认\n600511 国药股份\n### 实时行情与新闻分析\n仅供研究参考");
        ReflectionTestUtils.setField(chatService, "agentPlannerAssistant", planner);

        PlanValidator.ValidatedPlan result = chatService
                .planForExecution("请查询600511实时行情并分析相关新闻是否适合买入")
                .orElseThrow();

        assertEquals("600511.SH", result.plan().getSymbol());
        assertEquals(2, result.toolNames().size());
    }

    @Test
    void shouldRejectPlannerOutputWithoutCompleteJsonObject() {
        assertThrows(IllegalArgumentException.class,
                () -> ChatService.extractJsonObject("只有免责声明，没有计划"));
        assertThrows(IllegalArgumentException.class,
                () -> ChatService.extractJsonObject("{\"intent\":\"STOCK_ANALYSIS\""));
    }

    @Test
    void shouldExposeWebSearchResultsAsClickableKnowledgeSources() {
        ToolInvocation invocation = ToolInvocation.builder()
                .functionName("searchStockNewsAndAnnouncements")
                .success(true)
                .result("{\"success\":true,\"data\":[{"
                        + "\"title\":\"贵州茅台最新公告\",\"summary\":\"公告摘要\","
                        + "\"url\":\"https://example.com/news\",\"source\":\"示例财经\","
                        + "\"publishedAt\":\"2026-08-25\"}]}")
                .build();

        List<KnowledgeSource> sources = ChatService.extractWebSources(List.of(invocation));

        assertEquals(1, sources.size());
        assertEquals("贵州茅台最新公告", sources.get(0).getDocumentTitle());
        assertEquals("https://example.com/news", sources.get(0).getDocumentUrl());
        assertEquals("WEB", sources.get(0).getDocumentType());
    }
}
