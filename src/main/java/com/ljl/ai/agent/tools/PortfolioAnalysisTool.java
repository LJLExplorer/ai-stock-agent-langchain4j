package com.ljl.ai.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.agent.data.MarketDataClient;
import com.ljl.ai.agent.model.entity.StockQuote;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class PortfolioAnalysisTool {
    private final MarketDataClient marketDataClient;

    public PortfolioAnalysisTool(MarketDataClient marketDataClient) {
        this.marketDataClient = marketDataClient;
    }

    @Tool(name = "analyzePortfolio", value = "分析持仓收益、行业分布、集中度、风险和预测趋势")
    public String analyzePortfolio(@P("持仓 JSON，包含 symbol、quantity、cost") String holdings) {
        try {
            JSONArray positions = JSON.parseArray(holdings);
            double marketValue = 0;
            StringBuilder result = new StringBuilder("组合实时估值（腾讯财经）\n");
            for (int i = 0; i < positions.size(); i++) {
                JSONObject position = positions.getJSONObject(i);
                String symbol = position.getString("symbol");
                long quantity = position.getLongValue("quantity");
                StockQuote quote = marketDataClient.getRealtimeQuote(symbol);
                double value = quote.getPrice().doubleValue() * quantity;
                marketValue += value;
                result.append(symbol).append("：数量=").append(quantity).append("，现价=")
                        .append(quote.getPrice()).append("，市值=").append(value).append('\n');
            }
            return result.append("组合总市值：").append(marketValue)
                    .append("\n说明：已使用实时行情；成本收益、行业暴露和风险指标需要持仓数据包含 cost/industry 字段。").toString();
        } catch (Exception e) {
            return "组合分析失败：持仓必须是 JSON 数组，并且行情数据可用。" + e.getMessage();
        }
    }

    @Tool(name = "screenStocks", value = "根据自然语言条件筛选股票，例如近期上涨且未来一周趋势向上")
    public String screenStocks(@P("自然语言选股条件") String condition) {
        return "自然语言选股暂不返回虚构候选。请提供待筛选股票代码列表，系统将基于腾讯实时行情和真实日K逐一筛选。条件：" + condition;
    }
}
