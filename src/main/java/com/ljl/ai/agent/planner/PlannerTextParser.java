package com.ljl.ai.agent.planner;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从 Planner 的 Markdown 或自然语言兜底响应中提取受限股票分析计划。 */
public final class PlannerTextParser {

    private static final Pattern SYMBOL = Pattern.compile("(?<!\\d)(\\d{6})(?:\\.(SH|SZ))?(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    private PlannerTextParser() {
    }

    public static AgentPlan parse(String plannerText, String userMessage) {
        String planner = plannerText == null ? "" : plannerText;
        String combined = planner + "\n" + (userMessage == null ? "" : userMessage);
        Matcher matcher = SYMBOL.matcher(combined);
        if (!matcher.find()) {
            return null;
        }
        String rawSymbol = matcher.group(1);
        String market = matcher.group(2);
        String symbol = rawSymbol + (market == null
                ? (rawSymbol.startsWith("6") ? ".SH" : ".SZ")
                : "." + market.toUpperCase(Locale.ROOT));

        String normalized = combined.toLowerCase(Locale.ROOT);
        LinkedHashSet<StockAnalysisTask> tasks = new LinkedHashSet<>();
        if (containsAny(normalized, "实时", "行情", "涨跌", "价格", "报价", "成交量", "换手率",
                "market_data", "market data")) {
            tasks.add(StockAnalysisTask.MARKET_DATA);
        }
        if (containsAny(normalized, "技术", "macd", "rsi", "kdj", "均线", "趋势", "震荡",
                "technical_analysis", "technical analysis")) {
            tasks.add(StockAnalysisTask.TECHNICAL_ANALYSIS);
        }
        if (containsAny(normalized, "财务", "财报", "营收", "利润", "基本面", "financial_analysis",
                "financial analysis")) {
            tasks.add(StockAnalysisTask.FINANCIAL_ANALYSIS);
        }
        if (containsAny(normalized, "新闻", "公告", "舆情", "资讯", "消息", "购买", "买不买",
                "news_analysis", "news analysis")) {
            tasks.add(StockAnalysisTask.NEWS_ANALYSIS);
        }
        if (tasks.isEmpty()) {
            tasks.add(StockAnalysisTask.MARKET_DATA);
        }
        return AgentPlan.builder().intent("STOCK_ANALYSIS").symbol(symbol).tasks(new ArrayList<>(tasks)).build();
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
