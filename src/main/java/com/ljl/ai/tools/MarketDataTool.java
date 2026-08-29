package com.ljl.ai.tools;

import com.ljl.ai.client.MarketDataClient;
import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.model.entity.StockQuote;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;


@Slf4j
@Component
public class MarketDataTool {
    private final MarketDataClient marketDataClient;

    public MarketDataTool(MarketDataClient marketDataClient) {
        this.marketDataClient = marketDataClient;
    }

    @Tool(name = "getRealtimeQuote", value = "查询股票实时行情，包括价格、涨跌幅、成交量和换手率")
    public ToolResult<StockQuote> getRealtimeQuote(@P("股票代码，如 600519.SH 或 AAPL") String symbol) {
        log.info("查询实时行情, symbol: {}", symbol);
        return ToolResultExecutor.execute("MARKET_DATA_ERROR", () -> marketDataClient.getRealtimeQuote(symbol));
    }
}
