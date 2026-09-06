package com.ljl.ai.tools;

import java.math.BigDecimal;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.client.MarketDataClient;
import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.model.entity.StockQuote;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class PortfolioAnalysisTool {
    private final MarketDataClient marketDataClient;

    public PortfolioAnalysisTool(MarketDataClient marketDataClient) {
        this.marketDataClient = marketDataClient;
    }

    @Tool(name = "analyzePortfolio", value = "根据实时行情计算各持仓市值和组合总市值，不计算收益、行业分布或预测")
    public ToolResult<String> analyzePortfolio(@P("持仓 JSON 数组，包含 symbol 和正整数 quantity") String holdings) {
        return ToolResultExecutor.execute("PORTFOLIO_ANALYSIS_ERROR", () -> {
            JSONArray positions = JSON.parseArray(holdings);
            if (positions == null || positions.isEmpty()) {
                throw new IllegalArgumentException("持仓不能为空");
            }
            BigDecimal marketValue = BigDecimal.ZERO;
            StringBuilder result = new StringBuilder("组合实时估值（腾讯财经）\n");
            for (int i = 0; i < positions.size(); i++) {
                JSONObject position = positions.getJSONObject(i);
                String symbol = position.getString("symbol");
                long quantity = position.getBigDecimal("quantity").longValueExact();
                if (quantity <= 0) {
                    throw new IllegalArgumentException("持仓数量必须为正整数");
                }
                StockQuote quote = marketDataClient.getRealtimeQuote(symbol);
                BigDecimal value = quote.getPrice().multiply(BigDecimal.valueOf(quantity));
                marketValue = marketValue.add(value);
                result.append(symbol).append("：数量=").append(quantity).append("，现价=")
                        .append(quote.getPrice()).append("，市值=").append(value).append('\n');
            }
            return result.append("组合总市值：").append(marketValue)
                    .append("\n说明：仅计算市值，尚不支持成本收益、行业暴露和风险指标。").toString();
        });
    }

}
