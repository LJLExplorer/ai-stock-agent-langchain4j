package com.ljl.ai.research;

import com.ljl.ai.client.NewsSearchClient;
import com.ljl.ai.model.entity.StockQuote;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.workflow.ExecutionTask;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidencePackBuilderTest {

    private final EvidencePackBuilder builder = new EvidencePackBuilder();
    private final AnalysisContext context = new AnalysisContext("600519.SH", LocalDate.of(2025, 12, 31),
            AnalysisContext.ResearchMode.STANDARD, "execution-1", "trace-1", "user-1", "session-1");

    @Test
    void mapsOnlyValuesActuallyReturnedByTools() {
        StockQuote quote = StockQuote.builder()
                .symbol("600519.SH")
                .price(new BigDecimal("1500.00"))
                .changePercent(new BigDecimal("1.25"))
                .volume(123400L)
                .timestamp(LocalDateTime.of(2025, 12, 31, 15, 0))
                .build();
        NewsSearchClient.NewsItem news = new NewsSearchClient.NewsItem(
                "年度公告", "经营保持稳定", "https://example.test/news", "交易所",
                "2025-12-30T08:00:00Z", 0.8, FinancialFact.TemporalStatus.VERIFIED);

        List<FinancialFact> market = builder.map(StockAnalysisTask.MARKET_DATA, quote, context);
        List<FinancialFact> technical = builder.map(StockAnalysisTask.TECHNICAL_ANALYSIS,
                "数据截止日：2025-12-31\nMA5：1490；MA20：1450", context);
        List<FinancialFact> financial = builder.map(StockAnalysisTask.FINANCIAL_ANALYSIS,
                "报告期：2025-09-30\n披露日期：未知\n时点状态：UNKNOWN\n来源：Eastmoney", context);
        List<FinancialFact> newsFacts = builder.map(StockAnalysisTask.NEWS_ANALYSIS, List.of(news), context);

        assertEquals(List.of("price", "changePercent", "volume"),
                market.stream().map(FinancialFact::metric).toList());
        assertEquals(FinancialFact.EvidenceType.TECHNICAL, technical.get(0).evidenceType());
        assertEquals(FinancialFact.TemporalStatus.UNKNOWN, financial.get(0).temporalStatus());
        assertEquals("https://example.test/news", newsFacts.get(0).sourceUrl());
    }

    @Test
    void deduplicatesFactsRejectsFutureEvidenceAndBuildsStableHash() {
        FinancialFact verified = fact("close", LocalDate.of(2025, 12, 31), FinancialFact.TemporalStatus.VERIFIED);
        FinancialFact duplicate = fact("close", LocalDate.of(2025, 12, 31), FinancialFact.TemporalStatus.VERIFIED);
        FinancialFact future = fact("future", LocalDate.of(2026, 1, 1), FinancialFact.TemporalStatus.VERIFIED);
        ExecutionTask first = completed("market-1", List.of(verified, future));
        ExecutionTask second = completed("market-2", List.of(duplicate));
        ExecutionTask failed = ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS);
        failed.start();
        failed.fail("数据源不可用");

        List<ExecutionTask> tasks = new ArrayList<>(List.of(first, second, failed));
        EvidencePack pack = builder.build(context, tasks);
        Collections.reverse(tasks);
        EvidencePack reordered = builder.build(context, tasks);

        assertEquals(1, pack.evidenceByType().get(FinancialFact.EvidenceType.MARKET).size());
        assertEquals(pack.evidenceHash(), reordered.evidenceHash());
        assertTrue(pack.missingItems().contains("NEWS_ANALYSIS"));
        assertTrue(pack.toolFailures().stream().anyMatch(value -> value.contains("数据源不可用")));
        assertFalse(pack.modelView().contains("future"));
    }

    private ExecutionTask completed(String id, List<FinancialFact> evidence) {
        ExecutionTask task = ExecutionTask.pending(id, StockAnalysisTask.MARKET_DATA);
        task.start();
        task.complete("raw", evidence);
        return task;
    }

    private FinancialFact fact(String metric, LocalDate asOf, FinancialFact.TemporalStatus status) {
        return new FinancialFact(FinancialFact.EvidenceType.MARKET, metric, "100", "CNY", "CNY",
                asOf.toString(), asOf, asOf.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                "provider", null, Instant.parse("2026-09-05T01:00:00Z"), null, "snapshot", status);
    }
}
