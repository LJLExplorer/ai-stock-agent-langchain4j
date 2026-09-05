package com.ljl.ai.research;

import com.ljl.ai.client.MarketDataClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 使用历史行情确定性计算决策后验，不调用模型，也不读取聊天记忆。 */
@Slf4j
@Service
public class DecisionReviewService {
    private static final int HISTORY_LIMIT = 800;
    private static final int RETURN_SCALE = 6;

    private final MongoTemplate mongoTemplate;
    private final MarketDataClient marketDataClient;

    public DecisionReviewService(MongoTemplate mongoTemplate, MarketDataClient marketDataClient) {
        this.mongoTemplate = mongoTemplate;
        this.marketDataClient = marketDataClient;
    }

    public List<ResearchDecision> reviewDue(String userId, String symbol, LocalDate asOf) {
        if (userId == null || userId.isBlank() || symbol == null || symbol.isBlank() || asOf == null) {
            throw new IllegalArgumentException("userId、symbol 和 asOf 不能为空");
        }
        Query query = Query.query(Criteria.where("userId").is(userId.trim())
                .and("symbol").is(symbol.trim())
                .and("reviewStatus").in(ResearchDecision.ReviewStatus.PENDING.name(),
                        ResearchDecision.ReviewStatus.PARTIALLY_REVIEWED.name())
                .and("analysisDate").lte(asOf.minusDays(1)));
        List<ResearchDecision> candidates = mongoTemplate.find(query, ResearchDecision.class);
        List<ResearchDecision> reviewed = new ArrayList<>();
        for (ResearchDecision decision : candidates) {
            if (decision == null || decision.getReviewStatus() == ResearchDecision.ReviewStatus.COMPLETED) {
                continue;
            }
            if (review(decision, asOf)) {
                reviewed.add(mongoTemplate.save(decision));
            }
        }
        return List.copyOf(reviewed);
    }

    private boolean review(ResearchDecision decision, LocalDate asOf) {
        List<Bar> assetBars;
        try {
            assetBars = bars(marketDataClient.getDailyBars(decision.getSymbol(), HISTORY_LIMIT, asOf), asOf);
        } catch (Exception exception) {
            log.warn("decision_review_asset_data_failed decisionId={}, errorType={}",
                    decision.getDecisionId(), exception.getClass().getSimpleName());
            return false;
        }
        List<Bar> benchmarkBars;
        boolean benchmarkUnavailable = false;
        try {
            benchmarkBars = bars(marketDataClient.getDailyBars(
                    decision.getBenchmarkSymbol(), HISTORY_LIMIT, asOf), asOf);
        } catch (Exception exception) {
            benchmarkBars = List.of();
            benchmarkUnavailable = true;
            log.warn("decision_review_benchmark_data_failed decisionId={}, errorType={}",
                    decision.getDecisionId(), exception.getClass().getSimpleName());
        }

        Bar assetBase = base(assetBars, decision.getAnalysisDate());
        if (assetBase == null || assetBase.close().signum() == 0) {
            return false;
        }
        List<Bar> assetFuture = future(assetBars, decision.getAnalysisDate());
        Bar benchmarkBase = base(benchmarkBars, decision.getAnalysisDate());
        List<Bar> benchmarkFuture = future(benchmarkBars, decision.getAnalysisDate());
        Map<Integer, ResearchDecision.Outcome> additions = new LinkedHashMap<>();
        for (int horizon : decision.getEvaluationHorizons()) {
            if (decision.getOutcomes().containsKey(horizon) || assetFuture.size() < horizon) {
                continue;
            }
            Bar assetOutcome = assetFuture.get(horizon - 1);
            BigDecimal assetReturn = returnOf(assetBase.close(), assetOutcome.close());
            boolean benchmarkMissing = benchmarkUnavailable || benchmarkBase == null
                    || benchmarkBase.close().signum() == 0 || benchmarkFuture.size() < horizon;
            BigDecimal benchmarkReturn = benchmarkMissing ? null
                    : returnOf(benchmarkBase.close(), benchmarkFuture.get(horizon - 1).close());
            BigDecimal relativeReturn = benchmarkReturn == null ? null
                    : assetReturn.subtract(benchmarkReturn).setScale(RETURN_SCALE, RoundingMode.HALF_UP);
            additions.put(horizon, new ResearchDecision.Outcome(horizon, assetOutcome.date(), assetReturn,
                    benchmarkReturn, relativeReturn, benchmarkMissing));
        }
        if (additions.isEmpty()) {
            return false;
        }
        Map<Integer, ResearchDecision.Outcome> combined = new LinkedHashMap<>(decision.getOutcomes());
        combined.putAll(additions);
        decision.reviewed(additions, reflection(decision, combined));
        return true;
    }

    private String reflection(ResearchDecision decision, Map<Integer, ResearchDecision.Outcome> outcomes) {
        ResearchDecision.Outcome latest = outcomes.values().stream()
                .max(Comparator.comparingInt(ResearchDecision.Outcome::horizonTradingDays)).orElseThrow();
        StringBuilder text = new StringBuilder().append(latest.horizonTradingDays())
                .append("日后标的收益为 ").append(latest.assetReturn());
        if (latest.benchmarkMissing()) {
            return text.append("；基准数据缺失，未计算相对收益。").toString();
        }
        return text.append("，相对基准收益为 ").append(latest.relativeReturn())
                .append("；原评级为 ").append(decision.getRating()).append('。').toString();
    }

    private BigDecimal returnOf(BigDecimal start, BigDecimal end) {
        return end.subtract(start).divide(start, RETURN_SCALE, RoundingMode.HALF_UP);
    }

    private List<Bar> bars(List<MarketDataClient.DailyBar> values, LocalDate asOf) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(this::bar).filter(java.util.Objects::nonNull)
                .filter(bar -> !bar.date().isAfter(asOf)).sorted(Comparator.comparing(Bar::date)).toList();
    }

    private Bar bar(MarketDataClient.DailyBar value) {
        if (value == null || value.close() == null) {
            return null;
        }
        try {
            return new Bar(LocalDate.parse(value.date()), value.close());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Bar base(List<Bar> bars, LocalDate analysisDate) {
        return bars.stream().filter(bar -> !bar.date().isAfter(analysisDate))
                .max(Comparator.comparing(Bar::date)).orElse(null);
    }

    private List<Bar> future(List<Bar> bars, LocalDate analysisDate) {
        return bars.stream().filter(bar -> bar.date().isAfter(analysisDate)).toList();
    }

    private record Bar(LocalDate date, BigDecimal close) {
    }
}
