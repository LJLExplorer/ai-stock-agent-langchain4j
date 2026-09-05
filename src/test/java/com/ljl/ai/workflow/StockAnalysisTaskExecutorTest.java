package com.ljl.ai.workflow;

import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.model.entity.StockQuote;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.tools.FinancialAnalysisTool;
import com.ljl.ai.tools.MarketDataTool;
import com.ljl.ai.tools.NewsRagTool;
import com.ljl.ai.tools.TechnicalAnalysisTool;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockAnalysisTaskExecutorTest {

    @Test
    void shouldDispatchAllowedTaskToMatchingTool() {
        MarketDataTool market = mock(MarketDataTool.class);
        when(market.getRealtimeQuote("600519.SH"))
                .thenReturn(ToolResult.success(mock(StockQuote.class)));
        StockAnalysisTaskExecutor executor = new StockAnalysisTaskExecutor(
                market, mock(TechnicalAnalysisTool.class), mock(FinancialAnalysisTool.class), mock(NewsRagTool.class));

        ToolResult<?> result = executor.execute(StockAnalysisTask.MARKET_DATA, "600519.SH", "分析", "2024Q4");

        assertEquals(true, result.isSuccess());
        verify(market).getRealtimeQuote("600519.SH");
    }

    @Test
    void shouldRejectNullTaskInsteadOfCallingAnArbitraryTool() {
        StockAnalysisTaskExecutor executor = new StockAnalysisTaskExecutor(
                mock(MarketDataTool.class), mock(TechnicalAnalysisTool.class),
                mock(FinancialAnalysisTool.class), mock(NewsRagTool.class));

        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(null, "600519.SH", "分析", "2024Q4"));
    }

    @Test
    void shouldPassSameAnalysisContextToMarketAndTechnicalTools() {
        MarketDataTool market = mock(MarketDataTool.class);
        TechnicalAnalysisTool technical = mock(TechnicalAnalysisTool.class);
        AnalysisContext context = new AnalysisContext("600519.SH", LocalDate.of(2025, 12, 31),
                AnalysisContext.ResearchMode.STANDARD, "execution-1", "trace-1", "user-1", "session-1");
        when(market.getQuote("600519.SH", context)).thenReturn(ToolResult.success(mock(StockQuote.class)));
        when(technical.analyzeTechnicalIndicators("600519.SH", "1d", context))
                .thenReturn(ToolResult.success("technical"));
        StockAnalysisTaskExecutor executor = new StockAnalysisTaskExecutor(
                market, technical, mock(FinancialAnalysisTool.class), mock(NewsRagTool.class));

        executor.executeWithContext(StockAnalysisTask.MARKET_DATA, context, "分析", "2024Q4");
        executor.executeWithContext(StockAnalysisTask.TECHNICAL_ANALYSIS, context, "分析", "2024Q4");

        verify(market).getQuote(eq("600519.SH"), same(context));
        verify(technical).analyzeTechnicalIndicators(eq("600519.SH"), eq("1d"), same(context));
    }

    @Test
    void shouldPassSameAnalysisContextToFinancialAndNewsTools() {
        FinancialAnalysisTool financial = mock(FinancialAnalysisTool.class);
        NewsRagTool news = mock(NewsRagTool.class);
        AnalysisContext context = new AnalysisContext("600519.SH", LocalDate.of(2025, 12, 31),
                AnalysisContext.ResearchMode.STANDARD, "execution-1", "trace-1", "user-1", "session-1");
        when(financial.analyzeFinancialReport("600519.SH", "2025Q3", context))
                .thenReturn(ToolResult.success("financial"));
        when(news.searchStockNewsAndAnnouncements("600519.SH", "分析", 30, context))
                .thenReturn(ToolResult.success(java.util.List.of()));
        StockAnalysisTaskExecutor executor = new StockAnalysisTaskExecutor(
                mock(MarketDataTool.class), mock(TechnicalAnalysisTool.class), financial, news);

        executor.executeWithContext(StockAnalysisTask.FINANCIAL_ANALYSIS, context, "分析", "2025Q3");
        executor.executeWithContext(StockAnalysisTask.NEWS_ANALYSIS, context, "分析", "2025Q3");

        verify(financial).analyzeFinancialReport(eq("600519.SH"), eq("2025Q3"), same(context));
        verify(news).searchStockNewsAndAnnouncements(eq("600519.SH"), eq("分析"), eq(30), same(context));
    }
}
