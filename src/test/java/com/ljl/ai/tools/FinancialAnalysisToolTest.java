package com.ljl.ai.tools;

import com.ljl.ai.client.FinancialDataClient;
import com.ljl.ai.client.NewsSearchClient;
import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.research.FinancialFact;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinancialAnalysisToolTest {

    @Test
    void shouldExposeReportPublicationSourceAndTemporalStatus() throws Exception {
        FinancialDataClient client = mock(FinancialDataClient.class);
        AnalysisContext context = context();
        when(client.getLatest("600519.SH", "2025Q3", context.analysisDate())).thenReturn(
                new FinancialDataClient.FinancialSnapshot(
                        Map.of("reportDate", LocalDate.of(2025, 9, 30), "netProfit", "100", "source", "Eastmoney"),
                        LocalDate.of(2025, 9, 30), LocalDate.of(2025, 10, 25),
                        FinancialFact.TemporalStatus.VERIFIED));

        ToolResult<String> result = new FinancialAnalysisTool(client)
                .analyzeFinancialReport("600519.SH", "2025Q3", context);

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("报告期：2025-09-30"));
        assertTrue(result.getData().contains("披露日期：2025-10-25"));
        assertTrue(result.getData().contains("时点状态：VERIFIED"));
        assertTrue(result.getData().contains("Eastmoney"));
    }

    @Test
    void shouldReturnExplicitMissingWithoutFallingBackToCurrentData() throws Exception {
        FinancialDataClient client = mock(FinancialDataClient.class);
        AnalysisContext context = context();
        when(client.getLatest("600519.SH", "2025Q4", context.analysisDate()))
                .thenThrow(new IllegalStateException("分析日期前没有可用财务数据"));

        ToolResult<String> result = new FinancialAnalysisTool(client)
                .analyzeFinancialReport("600519.SH", "2025Q4", context);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("没有可用财务数据"));
        verify(client, never()).getLatest("600519.SH", "2025Q4");
    }

    @Test
    void newsToolUsesAnalysisDateAndPreservesProvenance() throws Exception {
        NewsSearchClient client = mock(NewsSearchClient.class);
        AnalysisContext context = context();
        NewsSearchClient.NewsItem item = new NewsSearchClient.NewsItem(
                "公告", "摘要", "https://example.test/news", "交易所", "2025-12-01T01:00:00Z",
                0.9, FinancialFact.TemporalStatus.VERIFIED);
        when(client.search("600519.SH", "公告", 30, 5, context.analysisDate())).thenReturn(List.of(item));

        ToolResult<List<NewsSearchClient.NewsItem>> result = new NewsRagTool(client)
                .searchStockNewsAndAnnouncements("600519.SH", "公告", 30, context);

        assertTrue(result.isSuccess());
        assertTrue(result.getData().get(0).url().contains("example.test"));
        assertTrue(result.getData().get(0).temporalStatus() == FinancialFact.TemporalStatus.VERIFIED);
        verify(client).search("600519.SH", "公告", 30, 5, context.analysisDate());
        verify(client, never()).search("600519.SH", "公告", 30, 5);
    }

    private AnalysisContext context() {
        return new AnalysisContext("600519.SH", LocalDate.of(2025, 12, 31),
                AnalysisContext.ResearchMode.STANDARD, "execution-1", "trace-1", "user-1", "session-1");
    }
}
