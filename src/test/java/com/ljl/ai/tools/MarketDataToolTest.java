package com.ljl.ai.tools;

import com.ljl.ai.client.MarketDataClient;
import com.ljl.ai.model.entity.StockQuote;
import com.ljl.ai.model.dto.ToolResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
}
