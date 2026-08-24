package com.ljl.ai.agent.tools;

import com.ljl.ai.agent.data.MarketDataClient;
import com.ljl.ai.agent.model.entity.StockQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketDataToolTest {

    @Test
    void shouldReturnErrorTextInsteadOfThrowingWhenClientFails() throws Exception {
        MarketDataClient client = mock(MarketDataClient.class);
        when(client.getRealtimeQuote("600519")).thenThrow(new IllegalStateException("腾讯行情接口返回空数据: 600519"));
        MarketDataTool tool = new MarketDataTool(client);

        String result = tool.getRealtimeQuote("600519");

        assertTrue(result.contains("实时行情查询失败"));
        assertTrue(result.contains("腾讯行情接口返回空数据"));
    }

    @Test
    void shouldReturnQuoteAsTextOnSuccess() throws Exception {
        MarketDataClient client = mock(MarketDataClient.class);
        when(client.getRealtimeQuote("600519")).thenReturn(
                StockQuote.builder().symbol("600519").name("贵州茅台").price(new BigDecimal("1500")).build());
        MarketDataTool tool = new MarketDataTool(client);

        String result = tool.getRealtimeQuote("600519");

        assertFalse(result.contains("查询失败"));
        assertTrue(result.contains("600519"));
    }
}
