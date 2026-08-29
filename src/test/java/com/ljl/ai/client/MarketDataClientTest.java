package com.ljl.ai.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketDataClientTest {
    @Test
    void shouldMapStockCodesToTheirMarkets() {
        assertEquals("sh600519", MarketDataClient.normalizeSymbol("600519"));
        assertEquals("sz000001", MarketDataClient.normalizeSymbol("000001"));
        assertEquals("bj830799", MarketDataClient.normalizeSymbol("830799"));
        assertEquals("bj430047", MarketDataClient.normalizeSymbol("430047"));
    }
}
