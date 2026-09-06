package com.ljl.ai.tools;

import com.ljl.ai.client.MarketDataClient;
import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.model.entity.StockQuote;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class StockComparisonTool {
    private final MarketDataClient marketDataClient;

    public StockComparisonTool(MarketDataClient marketDataClient) {
        this.marketDataClient = marketDataClient;
    }

    @Tool(name = "compareStocks", value = "仅比较多只股票当前实时行情，不提供历史周期、技术面、财务或预测比较")
    public ToolResult<String> compareStocks(@P("股票代码列表，逗号分隔") String symbols,
                                @P("比较周期，仅支持 realtime") String horizon) {
        return ToolResultExecutor.execute("STOCK_COMPARISON_ERROR", () -> {
            if (!"realtime".equalsIgnoreCase(horizon)) {
                throw new IllegalArgumentException("行情比较仅支持 realtime");
            }
            StringBuilder result = new StringBuilder("多股票实时行情比较（腾讯财经）\n");
            for (String symbol : symbols.split(",")) {
                StockQuote quote = marketDataClient.getRealtimeQuote(symbol.trim());
                result.append(quote.getSymbol()).append(" ").append(quote.getName())
                        .append("：价格=").append(quote.getPrice()).append("，涨跌=")
                        .append(quote.getChangePercent()).append("%，成交量=").append(quote.getVolume()).append('\n');
            }
            return result.toString();
        });
    }
}
