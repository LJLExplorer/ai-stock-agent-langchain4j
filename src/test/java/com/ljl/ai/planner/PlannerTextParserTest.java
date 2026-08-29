package com.ljl.ai.planner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTextParserTest {

    @Test
    void shouldReturnNullWhenNoSymbolFound() {
        assertNull(PlannerTextParser.parse("这是一段没有股票代码的文本", "随便问问"));
        assertNull(PlannerTextParser.parse(null, null));
    }

    @Test
    void shouldInferShanghaiSuffixForSixPrefix() {
        AgentPlan plan = PlannerTextParser.parse("查询 600519 的行情", null);

        assertEquals("600519.SH", plan.getSymbol());
    }

    @Test
    void shouldInferShenzhenSuffixForZeroAndThreePrefix() {
        assertEquals("000001.SZ", PlannerTextParser.parse("查询 000001 的行情", null).getSymbol());
        assertEquals("300750.SZ", PlannerTextParser.parse("查询 300750 的行情", null).getSymbol());
    }

    @Test
    void shouldInferBeijingSuffixForFourAndEightPrefix() {
        assertEquals("430047.BJ", PlannerTextParser.parse("查询 430047 的行情", null).getSymbol());
        assertEquals("830799.BJ", PlannerTextParser.parse("查询 830799 的行情", null).getSymbol());
    }

    @Test
    void shouldRespectExplicitMarketSuffixInText() {
        AgentPlan plan = PlannerTextParser.parse("查询 430047.BJ 的行情", null);

        assertEquals("430047.BJ", plan.getSymbol());
    }

    @Test
    void shouldMapKeywordsToTasks() {
        AgentPlan plan = PlannerTextParser.parse("600519 财报 营收 怎么样", null);

        assertEquals(List.of(StockAnalysisTask.FINANCIAL_ANALYSIS), plan.getTasks());
    }

    @Test
    void shouldDefaultToMarketDataWhenNoKeywordMatches() {
        AgentPlan plan = PlannerTextParser.parse("600519 到底怎么回事", null);

        assertTrue(plan.getTasks().contains(StockAnalysisTask.MARKET_DATA));
    }

    @Test
    void shouldIncludeNewsAnalysisForComprehensiveAdviceRequests() {
        // Regression: "综合分析...给出建议" used to only get NEWS_ANALYSIS when the
        // Planner's freely-generated reply happened to mention "新闻"/"公告" by chance.
        AgentPlan plan = PlannerTextParser.parse("您好！股票代码 600519 对应的是A股上市公司贵州茅台。",
                "综合分析下600519的实时行情，并给出建议");

        assertTrue(plan.getTasks().contains(StockAnalysisTask.NEWS_ANALYSIS));
    }

    @Test
    void shouldFallBackToUserMessageWhenPlannerTextHasNoSymbol() {
        AgentPlan plan = PlannerTextParser.parse("规划失败，无法解析", "帮我看看 300750 的技术指标");

        assertEquals("300750.SZ", plan.getSymbol());
        assertEquals(List.of(StockAnalysisTask.TECHNICAL_ANALYSIS), plan.getTasks());
    }
}
