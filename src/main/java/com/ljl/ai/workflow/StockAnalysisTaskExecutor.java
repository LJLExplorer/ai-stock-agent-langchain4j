package com.ljl.ai.workflow;

import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.tools.FinancialAnalysisTool;
import com.ljl.ai.tools.MarketDataTool;
import com.ljl.ai.tools.NewsRagTool;
import com.ljl.ai.tools.TechnicalAnalysisTool;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 股票分析任务到业务 Tool 的唯一受控入口。
 */
@Component
public class StockAnalysisTaskExecutor {

    private final MarketDataTool marketDataTool;
    private final TechnicalAnalysisTool technicalAnalysisTool;
    private final FinancialAnalysisTool financialAnalysisTool;
    private final NewsRagTool newsRagTool;

    public StockAnalysisTaskExecutor(MarketDataTool marketDataTool,
                                     TechnicalAnalysisTool technicalAnalysisTool,
                                     FinancialAnalysisTool financialAnalysisTool,
                                     NewsRagTool newsRagTool) {
        this.marketDataTool = marketDataTool;
        this.technicalAnalysisTool = technicalAnalysisTool;
        this.financialAnalysisTool = financialAnalysisTool;
        this.newsRagTool = newsRagTool;
    }

    /** 无显式分析上下文的兼容入口，按固定任务映射调用工具，并为技术、财务快照使用当前市场日期。 */
    public ToolResult<?> execute(StockAnalysisTask task, String symbol, String query, String period) {
        if (task == null) {
            throw new IllegalArgumentException("股票分析任务不能为空");
        }
        return switch (task) {
            case MARKET_DATA -> marketDataTool.getRealtimeQuote(symbol);
            case TECHNICAL_ANALYSIS -> technicalAnalysisTool.technicalSnapshot(symbol, "1d", liveContext(symbol));
            case FINANCIAL_ANALYSIS -> financialAnalysisTool.financialSnapshot(symbol, period, liveContext(symbol));
            case NEWS_ANALYSIS -> newsRagTool.searchStockNewsAndAnnouncements(symbol, query, 30);
        };
    }

    /** 将已校验任务确定性映射到业务工具，并向各工具传递同一个分析标的和截止日期。 */
    public ToolResult<?> executeWithContext(StockAnalysisTask task, AnalysisContext context, String query, String period) {
        if (task == null) {
            throw new IllegalArgumentException("股票分析任务不能为空");
        }
        if (context == null) {
            throw new IllegalArgumentException("AnalysisContext 不能为空");
        }
        String symbol = context.symbol();
        return switch (task) {
            case MARKET_DATA -> marketDataTool.getQuote(symbol, context);
            case TECHNICAL_ANALYSIS -> technicalAnalysisTool.technicalSnapshot(symbol, "1d", context);
            case FINANCIAL_ANALYSIS -> financialAnalysisTool.financialSnapshot(symbol, period, context);
            case NEWS_ANALYSIS -> newsRagTool.searchStockNewsAndAnnouncements(symbol, query, 30, context);
        };
    }

    private AnalysisContext liveContext(String symbol) {
        return new AnalysisContext(symbol, LocalDate.now(ZoneId.of("Asia/Shanghai")),
                AnalysisContext.ResearchMode.STANDARD, null, null, null, null);
    }
}
