package com.ljl.ai.tools;

import com.ljl.ai.client.MarketDataClient;
import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.model.entity.StockQuote;
import com.ljl.ai.research.AnalysisContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

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

    /** 工作流专用入口：历史分析使用截止日 K 线快照，今天才读取实时行情。 */
    public ToolResult<StockQuote> getQuote(String symbol, AnalysisContext context) {
        if (context == null) {
            throw new IllegalArgumentException("AnalysisContext 不能为空");
        }
        if (LocalDate.now().equals(context.analysisDate())) {
            return getRealtimeQuote(symbol);
        }
        log.info("查询历史行情, symbol: {}, analysisDate: {}", symbol, context.analysisDate());
        return ToolResultExecutor.execute("MARKET_DATA_ERROR", () -> {
            var bars = marketDataClient.getDailyBars(symbol, 2, context.analysisDate());
            var latest = bars.get(bars.size() - 1);
            BigDecimal changePercent = null;
            if (bars.size() > 1) {
                BigDecimal previous = bars.get(bars.size() - 2).close();
                if (previous != null && previous.signum() != 0) {
                    changePercent = latest.close().subtract(previous)
                            .divide(previous, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                }
            }
            return StockQuote.builder()
                    .symbol(symbol)
                    .price(latest.close())
                    .changePercent(changePercent)
                    .volume(latest.volume())
                    .timestamp(LocalDate.parse(latest.date()).atTime(15, 0))
                    .build();
        });
    }
}
