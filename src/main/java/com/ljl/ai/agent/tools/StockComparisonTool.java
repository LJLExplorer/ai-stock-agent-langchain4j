package com.ljl.ai.agent.tools;

import com.ljl.ai.agent.data.MarketDataClient;
import com.ljl.ai.agent.model.entity.StockQuote;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class StockComparisonTool {
    private final MarketDataClient marketDataClient;

    public StockComparisonTool(MarketDataClient marketDataClient) {
        this.marketDataClient = marketDataClient;
    }

    @Tool(name = "compareStocks", value = "比较多只股票的行情、技术面、基本面和预测结果")
    public String compareStocks(@P("股票代码列表，逗号分隔") String symbols,
                                @P("比较周期") String horizon) {
        StringBuilder result = new StringBuilder("多股票实时行情比较（腾讯财经）\n周期：").append(horizon).append('\n');
        for (String symbol : symbols.split(",")) {
            try {
                StockQuote quote = marketDataClient.getRealtimeQuote(symbol.trim());
                result.append(quote.getSymbol()).append(" ").append(quote.getName())
                        .append("：价格=").append(quote.getPrice()).append("，涨跌=")
                        .append(quote.getChangePercent()).append("%，成交量=").append(quote.getVolume()).append('\n');
            } catch (Exception e) {
                result.append(symbol.trim()).append("：查询失败=").append(e.getMessage()).append('\n');
            }
        }
        return result.toString();
    }
}
