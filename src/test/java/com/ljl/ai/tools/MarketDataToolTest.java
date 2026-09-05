package com.ljl.ai.tools;

import com.ljl.ai.client.MarketDataClient;
import com.ljl.ai.model.entity.StockQuote;
import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.research.AnalysisContext;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class MarketDataToolTest {

    @Test
    void shouldReturnErrorTextInsteadOfThrowingWhenClientFails() throws Exception {
        MarketDataClient client = mock(MarketDataClient.class);
        when(client.getRealtimeQuote("600519")).thenThrow(new IllegalStateException("腾讯行情接口返回空数据: 600519"));
        MarketDataTool tool = new MarketDataTool(client);

        ToolResult<?> result = tool.getRealtimeQuote("600519");

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("腾讯行情接口返回空数据"));
    }

    @Test
    void shouldReturnQuoteAsTextOnSuccess() throws Exception {
        MarketDataClient client = mock(MarketDataClient.class);
        when(client.getRealtimeQuote("600519")).thenReturn(
                StockQuote.builder().symbol("600519").name("贵州茅台").price(new BigDecimal("1500")).build());
        MarketDataTool tool = new MarketDataTool(client);

        ToolResult<StockQuote> result = tool.getRealtimeQuote("600519");

        assertTrue(result.isSuccess());
        assertEquals("600519", result.getData().getSymbol());
    }

    @Test
    void shouldBuildHistoricalQuoteFromBarsAtAnalysisDate() throws Exception {
        MarketDataClient client = mock(MarketDataClient.class);
        LocalDate analysisDate = LocalDate.of(2025, 12, 31);
        when(client.getDailyBars("600519.SH", 2, analysisDate)).thenReturn(List.of(
                bar("2025-12-30", "100.00", 1000),
                bar("2025-12-31", "105.00", 1200)
        ));
        MarketDataTool tool = new MarketDataTool(client);
        AnalysisContext context = new AnalysisContext("600519.SH", analysisDate,
                AnalysisContext.ResearchMode.STANDARD, "execution-1", "trace-1", "user-1", "session-1");

        ToolResult<StockQuote> result = tool.getQuote("600519.SH", context);

        assertTrue(result.isSuccess());
        assertEquals(new BigDecimal("105.00"), result.getData().getPrice());
        assertEquals(new BigDecimal("5.0000"), result.getData().getChangePercent());
        assertEquals(analysisDate, result.getData().getTimestamp().toLocalDate());
        verify(client).getDailyBars("600519.SH", 2, analysisDate);
        verify(client, never()).getRealtimeQuote("600519.SH");
    }

    @Test
    void technicalToolDescriptionOnlyClaimsImplementedIndicators() throws Exception {
        Tool annotation = TechnicalAnalysisTool.class
                .getMethod("analyzeTechnicalIndicators", String.class, String.class)
                .getAnnotation(Tool.class);
        String description = String.join(" ", annotation.value());

        assertTrue(description.contains("MA5"));
        assertTrue(description.contains("MA20"));
        assertFalse(description.contains("MACD"));
        assertFalse(description.contains("RSI"));
        assertFalse(description.contains("KDJ"));
        assertFalse(description.contains("布林带"));
    }

    private MarketDataClient.DailyBar bar(String date, String close, long volume) {
        BigDecimal price = new BigDecimal(close);
        return new MarketDataClient.DailyBar(date, price, price, price, price, volume);
    }
}
