package com.ljl.ai.service;

import com.ljl.ai.model.entity.KnowledgeSource;
import com.ljl.ai.model.entity.ToolInvocation;
import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.research.EvidencePack;
import com.ljl.ai.research.FinancialFact;
import com.ljl.ai.workflow.ExecutionState;
import com.ljl.ai.workflow.ExecutionTask;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResponseAssemblerTest {

    @Test
    void shouldCollectOnlyCurrentTurnAndKeepStructuredToolFailure() {
        ResponseAssembler assembler = new ResponseAssembler();
        var provider = mock(com.ljl.ai.memory.RedisChatMemoryProvider.class);
        var memory = mock(dev.langchain4j.memory.ChatMemory.class);
        ReflectionTestUtils.setField(assembler, "chatMemoryProvider", provider);
        when(provider.get("memory")).thenReturn(memory);
        var oldRequest = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("old").name("getRealtimeQuote").arguments("{}").build();
        var newRequest = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("new").name("analyzeFinancialReport").arguments("{}").build();
        var oldMessage = dev.langchain4j.data.message.AiMessage.from(oldRequest);
        when(memory.messages()).thenReturn(List.of(oldMessage));
        var previousIds = assembler.collectToolInvocationIds("memory");
        when(memory.messages()).thenReturn(List.of(oldMessage,
                dev.langchain4j.data.message.ToolExecutionResultMessage.from(oldRequest, "旧结果"),
                dev.langchain4j.data.message.AiMessage.from(newRequest),
                dev.langchain4j.data.message.ToolExecutionResultMessage.from(newRequest,
                        "{\"success\":false,\"errorMessage\":\"DATA_UNAVAILABLE\",\"costTime\":42}")));

        var content = assembler.assemble("memory", previousIds, null,
                new AgentExecutionService.AgentResult("回答", null));

        assertEquals(1, content.toolInvocations().size());
        var invocation = content.toolInvocations().getFirst();
        assertEquals("analyzeFinancialReport", invocation.getFunctionName());
        assertEquals(false, invocation.getSuccess());
        assertEquals("DATA_UNAVAILABLE", invocation.getErrorMessage());
        assertEquals(42L, invocation.getExecutionTime());
    }

    @Test
    void shouldDeduplicateWebSourcesAndPreserveRagSourceMetadata() {
        ResponseAssembler assembler = new ResponseAssembler();
        ReflectionTestUtils.setField(assembler, "chatMemoryProvider",
                mock(com.ljl.ai.memory.RedisChatMemoryProvider.class));
        KnowledgeSource ragSource = KnowledgeSource.builder().documentId("doc").documentTitle("原研报")
                .documentUrl("https://example.com/news").build();
        ExecutionTask news = ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS);
        news.start();
        news.complete("[{\"title\":\"最新公告\",\"url\":\"https://example.com/news\"}]");
        ExecutionState state = ExecutionState.planned("exec", "session", "问题", List.of(news));

        var content = assembler.assemble("memory", java.util.Set.of(), List.of(ragSource),
                new AgentExecutionService.AgentResult("回答", state));

        assertEquals(List.of(ragSource), content.knowledgeSources());
        assertEquals(1, content.toolInvocations().size());
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

        List<KnowledgeSource> sources = ResponseAssembler.extractWebSources(List.of(invocation));

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

        List<KnowledgeSource> sources = ResponseAssembler.extractEvidenceSources(pack);

        assertEquals(1, sources.size());
        assertEquals("ev-technical", sources.get(0).getDocumentId());
        assertEquals("Tencent Finance", sources.get(0).getDocumentTitle());
        assertEquals("EVIDENCE", sources.get(0).getDocumentType());
        assertEquals("https://gu.qq.com/sh600519/gp", sources.get(0).getDocumentUrl());
        assertTrue(sources.get(0).getContentSnippet().contains("MA5=1305.09"));
    }

    @Test
    void shouldExposeWorkflowTasksAsToolInvocationsAndNewsSources() {
        ExecutionTask market = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        market.start();
        market.complete("{\"symbol\":\"600519.SH\"}");
        ExecutionTask news = ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS);
        news.start();
        news.complete("[{\"title\":\"最新公告\",\"url\":\"https://example.com/news\",\"source\":\"示例财经\"}]");
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析", List.of(market, news));
        state.setPlan(AgentPlan.builder().symbol("600519.SH").tasks(List.of()).build());

        var invocations = ResponseAssembler.workflowToolInvocations(state);

        assertEquals(2, invocations.size());
        assertTrue(invocations.stream().allMatch(invocation -> Boolean.TRUE.equals(invocation.getSuccess())));
        assertEquals("https://example.com/news", ResponseAssembler.extractWebSources(invocations).getFirst().getDocumentUrl());
    }

}
