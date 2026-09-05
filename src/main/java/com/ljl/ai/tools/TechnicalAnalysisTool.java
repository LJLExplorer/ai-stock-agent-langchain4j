package com.ljl.ai.tools;

import com.ljl.ai.client.MarketDataClient;
import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.research.AnalysisContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
public class TechnicalAnalysisTool {
    private final MarketDataClient marketDataClient;

    public TechnicalAnalysisTool(MarketDataClient marketDataClient) {
        this.marketDataClient = marketDataClient;
    }

    @Tool(name = "analyzeTechnicalIndicators", value = "基于真实日K计算股票最新收盘、日涨跌、MA5、MA20和均线趋势")
    public ToolResult<String> analyzeTechnicalIndicators(@P("股票代码") String symbol,
                                             @P("分析周期，如 1d/1h") String period) {
        return analyze(symbol, period, LocalDate.now());
    }

    /** 工作流专用入口，确保技术指标只使用分析截止日及之前的数据。 */
    public ToolResult<String> analyzeTechnicalIndicators(String symbol, String period, AnalysisContext context) {
        if (context == null) {
            throw new IllegalArgumentException("AnalysisContext 不能为空");
        }
        return analyze(symbol, period, context.analysisDate());
    }

    private ToolResult<String> analyze(String symbol, String period, LocalDate analysisDate) {
        log.info("技术分析, symbol: {}, period: {}, analysisDate: {}", symbol, period, analysisDate);
        return ToolResultExecutor.execute("TECHNICAL_ANALYSIS_ERROR", () -> {
            List<MarketDataClient.DailyBar> bars = marketDataClient.getDailyBars(symbol, 60, analysisDate);
            if (bars.size() < 20) {
                throw new IllegalStateException("历史K线不足20条");
            }
            BigDecimal close = bars.get(bars.size() - 1).close();
            BigDecimal ma20 = average(bars, 20, 2);
            BigDecimal ma5 = average(bars, 5, 2);
            BigDecimal previous = bars.get(bars.size() - 2).close();
            BigDecimal change = close.subtract(previous).divide(previous, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            return "技术分析（腾讯财经真实日K）\n股票：" + symbol + "，周期：" + period
                    + "，数据截止日：" + bars.get(bars.size() - 1).date()
                    + "\n最新收盘：" + close + "；日涨跌：" + change + "%；MA5：" + ma5 + "；MA20：" + ma20
                    + "\n趋势判断：" + (close.compareTo(ma20) >= 0 ? "收盘位于MA20上方" : "收盘位于MA20下方")
                    + "。\n说明：当前仅计算基础均线与日涨跌。";
        });
    }

    private static BigDecimal average(List<MarketDataClient.DailyBar> bars, int count, int scale) {
        return bars.subList(bars.size() - count, bars.size()).stream().map(MarketDataClient.DailyBar::close)
                .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(count), scale, RoundingMode.HALF_UP);
    }
}
