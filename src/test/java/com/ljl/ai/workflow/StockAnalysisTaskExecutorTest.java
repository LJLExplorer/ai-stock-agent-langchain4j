package com.ljl.ai.workflow;

import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.model.entity.StockQuote;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.tools.FinancialAnalysisTool;
import com.ljl.ai.tools.MarketDataTool;
import com.ljl.ai.tools.NewsRagTool;
import com.ljl.ai.tools.TechnicalAnalysisTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
}
