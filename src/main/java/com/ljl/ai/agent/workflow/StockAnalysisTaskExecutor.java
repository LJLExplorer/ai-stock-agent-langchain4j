package com.ljl.ai.agent.workflow;

import com.ljl.ai.agent.model.dto.ToolResult;
import com.ljl.ai.agent.planner.StockAnalysisTask;
import com.ljl.ai.agent.tools.FinancialAnalysisTool;
import com.ljl.ai.agent.tools.MarketDataTool;
import com.ljl.ai.agent.tools.NewsRagTool;
import com.ljl.ai.agent.tools.TechnicalAnalysisTool;
import org.springframework.stereotype.Component;

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

    public ToolResult<?> execute(StockAnalysisTask task, String symbol, String query, String period) {
        if (task == null) {
            throw new IllegalArgumentException("股票分析任务不能为空");
        }
        return switch (task) {
            case MARKET_DATA -> marketDataTool.getRealtimeQuote(symbol);
            case TECHNICAL_ANALYSIS -> technicalAnalysisTool.analyzeTechnicalIndicators(symbol, "1d");
            case FINANCIAL_ANALYSIS -> financialAnalysisTool.analyzeFinancialReport(symbol, period);
            case NEWS_ANALYSIS -> newsRagTool.searchStockNewsAndAnnouncements(symbol, query, 30);
        };
    }
}
