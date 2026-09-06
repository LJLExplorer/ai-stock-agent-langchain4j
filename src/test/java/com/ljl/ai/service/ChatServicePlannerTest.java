package com.ljl.ai.service;

import com.ljl.ai.agent.AgentPlannerAssistant;
import com.ljl.ai.model.entity.KnowledgeSource;
import com.ljl.ai.model.entity.ToolInvocation;
import com.ljl.ai.planner.PlanValidator;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.research.EvidencePack;
import com.ljl.ai.research.FinancialFact;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.time.LocalDate;

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
        verify(planner).plan("分析贵州茅台最近为什么跌");
    }

    @Test
    void shouldPlanExplicitStockCodeLocallyWithoutCallingPlanner() {
        ChatService chatService = new ChatService();
        AgentPlannerAssistant planner = mock(AgentPlannerAssistant.class);
        ReflectionTestUtils.setField(chatService, "agentPlannerAssistant", planner);

        PlanValidator.ValidatedPlan result = chatService.planForExecution("请对600519做技术分析").orElseThrow();

        assertEquals("600519.SH", result.plan().getSymbol());
        assertEquals(List.of(StockAnalysisTask.TECHNICAL_ANALYSIS), result.plan().getTasks());
        verifyNoInteractions(planner);
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
    void shouldPreferRestrictedUserIntentOverVerbosePlannerResponse() {
        ChatService chatService = new ChatService();
        AgentPlannerAssistant planner = mock(AgentPlannerAssistant.class);
        when(planner.plan("查询600511并给出购买建议")).thenReturn(
                "为您查询到 **国药股份（600511.SH）** 的最新实时行情如下：\n"
                        + "* **当前价格**：26.71元\n* **今日涨跌**：上涨 1.71%\n"
                        + "**今日行情简析**：整体呈现震荡上涨态势。\n"
                        + "关于您之前提到的“分析相关新闻及购买建议”，建议结合近期公司财报判断。");
        ReflectionTestUtils.setField(chatService, "agentPlannerAssistant", planner);

        PlanValidator.ValidatedPlan result = chatService.planForExecution("查询600511并给出购买建议").orElseThrow();

        assertEquals("600511.SH", result.plan().getSymbol());
        assertEquals(List.of(StockAnalysisTask.NEWS_ANALYSIS), result.plan().getTasks());
        verifyNoInteractions(planner);
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

    @Test
    void shouldExposeWorkflowEvidenceAsReadableClickableSources() {
        LocalDate asOf = LocalDate.of(2026, 9, 4);
        FinancialFact fact = new FinancialFact("ev-technical", FinancialFact.EvidenceType.TECHNICAL,
                "technical_analysis", "MA5=1305.09", null, null, asOf.toString(), asOf,
                Instant.parse("2026-09-04T07:00:00Z"), "Tencent Finance",
                "https://gu.qq.com/sh600519/gp", Instant.parse("2026-09-06T05:00:00Z"),
                null, null, FinancialFact.TemporalStatus.VERIFIED);
        EvidencePack pack = new EvidencePack(null,
                Map.of(FinancialFact.EvidenceType.TECHNICAL, List.of(fact)), List.of(), List.of(),
                Instant.parse("2026-09-04T07:00:00Z"), "hash", "model-view");

        List<KnowledgeSource> sources = ChatService.extractEvidenceSources(pack);

        assertEquals(1, sources.size());
        assertEquals("ev-technical", sources.get(0).getDocumentId());
        assertEquals("Tencent Finance", sources.get(0).getDocumentTitle());
        assertEquals("EVIDENCE", sources.get(0).getDocumentType());
        assertEquals("https://gu.qq.com/sh600519/gp", sources.get(0).getDocumentUrl());
        assertTrue(sources.get(0).getContentSnippet().contains("MA5=1305.09"));
    }
}
