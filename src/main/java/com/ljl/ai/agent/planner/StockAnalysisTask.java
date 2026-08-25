package com.ljl.ai.agent.planner;

/**
 * 第一版股票分析允许执行的任务。
 */
public enum StockAnalysisTask {
    MARKET_DATA("getRealtimeQuote"),
    TECHNICAL_ANALYSIS("analyzeTechnicalIndicators"),
    FINANCIAL_ANALYSIS("analyzeFinancialReport"),
    NEWS_ANALYSIS("searchStockNewsAndAnnouncements");

    private final String toolName;

    StockAnalysisTask(String toolName) {
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }
}
