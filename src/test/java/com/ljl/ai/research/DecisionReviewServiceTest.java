package com.ljl.ai.research;

import com.ljl.ai.client.MarketDataClient;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DecisionReviewServiceTest {

    @Test
    void shouldCalculateOneFiveTwentyDayAndRelativeBenchmarkReturns() throws Exception {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MarketDataClient marketDataClient = mock(MarketDataClient.class);
        ResearchDecision decision = pendingDecision();
        when(mongoTemplate.find(any(Query.class), eq(ResearchDecision.class))).thenReturn(List.of(decision));
        when(mongoTemplate.save(any(ResearchDecision.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketDataClient.getDailyBars(eq("600519.SH"), eq(800), any(LocalDate.class)))
                .thenReturn(bars("100", "101", "105", "120"));
        when(marketDataClient.getDailyBars(eq("510300.SH"), eq(800), any(LocalDate.class)))
                .thenReturn(bars("200", "202", "210", "220"));
        DecisionReviewService service = new DecisionReviewService(mongoTemplate, marketDataClient);

        List<ResearchDecision> reviewed = service.reviewDue(
                "user-1", "600519.SH", LocalDate.of(2026, 2, 2));

        assertThat(reviewed).containsExactly(decision);
        assertThat(decision.getOutcomes()).containsOnlyKeys(1, 5, 20);
        assertThat(decision.getOutcomes().get(1).assetReturn()).isEqualByComparingTo("0.010000");
        assertThat(decision.getOutcomes().get(5).assetReturn()).isEqualByComparingTo("0.050000");
        assertThat(decision.getOutcomes().get(20).assetReturn()).isEqualByComparingTo("0.200000");
        assertThat(decision.getOutcomes().get(20).benchmarkReturn()).isEqualByComparingTo("0.100000");
        assertThat(decision.getOutcomes().get(20).relativeReturn()).isEqualByComparingTo("0.100000");
        assertThat(decision.getReviewStatus()).isEqualTo(ResearchDecision.ReviewStatus.COMPLETED);
        assertThat(decision.getOutcomeAvailableAt()).isEqualTo(LocalDate.of(2026, 1, 20));
        assertThat(decision.getReflection()).contains("20日", "相对基准");
    }

    @Test
    void shouldNotEvaluateDecisionBeforeAnyFutureTradingBarExists() throws Exception {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MarketDataClient marketDataClient = mock(MarketDataClient.class);
        ResearchDecision decision = pendingDecision();
        when(mongoTemplate.find(any(Query.class), eq(ResearchDecision.class))).thenReturn(List.of(decision));
        when(marketDataClient.getDailyBars(eq("600519.SH"), eq(800), any(LocalDate.class)))
                .thenReturn(List.of(bar(LocalDate.of(2025, 12, 31), "100")));
        when(marketDataClient.getDailyBars(eq("510300.SH"), eq(800), any(LocalDate.class)))
                .thenReturn(List.of(bar(LocalDate.of(2025, 12, 31), "200")));

        List<ResearchDecision> reviewed = new DecisionReviewService(mongoTemplate, marketDataClient)
                .reviewDue("user-1", "600519.SH", LocalDate.of(2025, 12, 31));

        assertThat(reviewed).isEmpty();
        assertThat(decision.getOutcomes()).isEmpty();
        verify(mongoTemplate, never()).save(any(ResearchDecision.class));
    }

    @Test
    void shouldKeepAssetReturnAndMarkBenchmarkMissing() throws Exception {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MarketDataClient marketDataClient = mock(MarketDataClient.class);
        ResearchDecision decision = pendingDecision();
        when(mongoTemplate.find(any(Query.class), eq(ResearchDecision.class))).thenReturn(List.of(decision));
        when(mongoTemplate.save(any(ResearchDecision.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketDataClient.getDailyBars(eq("600519.SH"), eq(800), any(LocalDate.class)))
                .thenReturn(bars("100", "101", "105", "120"));
        when(marketDataClient.getDailyBars(eq("510300.SH"), eq(800), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("benchmark unavailable"));

        new DecisionReviewService(mongoTemplate, marketDataClient)
                .reviewDue("user-1", "600519.SH", LocalDate.of(2026, 2, 2));

        ResearchDecision.Outcome outcome = decision.getOutcomes().get(20);
        assertThat(outcome.assetReturn()).isEqualByComparingTo("0.200000");
        assertThat(outcome.benchmarkMissing()).isTrue();
        assertThat(outcome.benchmarkReturn()).isNull();
        assertThat(outcome.relativeReturn()).isNull();
        assertThat(decision.getReflection()).contains("基准数据缺失");
    }

    @Test
    void shouldQueryOwnerSymbolPendingStatusAndUpperDateAndRemainIdempotent() throws Exception {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MarketDataClient marketDataClient = mock(MarketDataClient.class);
        ResearchDecision decision = pendingDecision();
        when(mongoTemplate.find(any(Query.class), eq(ResearchDecision.class)))
                .thenReturn(List.of(decision), List.of(decision));
        when(mongoTemplate.save(any(ResearchDecision.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketDataClient.getDailyBars(eq("600519.SH"), eq(800), any(LocalDate.class)))
                .thenReturn(bars("100", "101", "105", "120"));
        when(marketDataClient.getDailyBars(eq("510300.SH"), eq(800), any(LocalDate.class)))
                .thenReturn(bars("200", "202", "210", "220"));
        DecisionReviewService service = new DecisionReviewService(mongoTemplate, marketDataClient);
        LocalDate asOf = LocalDate.of(2026, 2, 2);

        service.reviewDue("user-1", "600519.SH", asOf);
        service.reviewDue("user-1", "600519.SH", asOf);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, times(2)).find(query.capture(), eq(ResearchDecision.class));
        Document criteria = query.getAllValues().getFirst().getQueryObject();
        assertThat(criteria.getString("userId")).isEqualTo("user-1");
        assertThat(criteria.getString("symbol")).isEqualTo("600519.SH");
        assertThat((Document) criteria.get("reviewStatus")).containsKey("$in");
        assertThat((Document) criteria.get("analysisDate")).containsEntry("$lte", asOf.minusDays(1));
        verify(mongoTemplate, times(1)).save(decision);
    }

    private ResearchDecision pendingDecision() {
        return ResearchDecision.pending("decision-1", "exec-1", "user-1", "600519.SH",
                LocalDate.of(2025, 12, 31), ResearchConclusion.Rating.BULLISH,
                0.8, "hash", "summary", "graph-v1");
    }

    private List<MarketDataClient.DailyBar> bars(String base, String day1, String day5, String day20) {
        List<MarketDataClient.DailyBar> bars = new ArrayList<>();
        bars.add(bar(LocalDate.of(2025, 12, 31), base));
        for (int day = 1; day <= 20; day++) {
            String close = day == 1 ? day1 : day == 5 ? day5 : day == 20 ? day20 : day1;
            bars.add(bar(LocalDate.of(2025, 12, 31).plusDays(day), close));
        }
        return bars;
    }

    private MarketDataClient.DailyBar bar(LocalDate date, String close) {
        BigDecimal value = new BigDecimal(close);
        return new MarketDataClient.DailyBar(date.toString(), value, value, value, value, 1_000L);
    }
}
