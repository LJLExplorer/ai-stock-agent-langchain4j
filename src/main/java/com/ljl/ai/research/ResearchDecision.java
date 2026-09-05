package com.ljl.ai.research;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 与聊天和偏好记忆隔离保存的结构化投研决策。 */
@Data
@NoArgsConstructor
@Document(collection = "research_decisions")
public class ResearchDecision {
    public static final List<Integer> DEFAULT_HORIZONS = List.of(1, 5, 20);
    public static final String DEFAULT_BENCHMARK = "510300.SH";

    @Id
    private String decisionId;
    private String executionId;
    private String userId;
    private String symbol;
    private LocalDate analysisDate;
    private ResearchConclusion.Rating rating;
    private double confidence;
    private String evidenceHash;
    private String summary;
    private String graphVersion;
    private String benchmarkSymbol = DEFAULT_BENCHMARK;
    private List<Integer> evaluationHorizons = DEFAULT_HORIZONS;
    private Map<Integer, Outcome> outcomes = new LinkedHashMap<>();
    private ReviewStatus reviewStatus = ReviewStatus.PENDING;
    private LocalDate outcomeAvailableAt;
    private String reflection;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ResearchDecision pending(String decisionId, String executionId, String userId,
                                           String symbol, LocalDate analysisDate,
                                           ResearchConclusion.Rating rating, double confidence,
                                           String evidenceHash, String summary, String graphVersion) {
        ResearchDecision decision = new ResearchDecision();
        decision.decisionId = required(decisionId, "decisionId");
        decision.executionId = required(executionId, "executionId");
        decision.userId = required(userId, "userId");
        decision.symbol = required(symbol, "symbol");
        decision.analysisDate = java.util.Objects.requireNonNull(analysisDate, "analysisDate 不能为空");
        decision.rating = java.util.Objects.requireNonNull(rating, "rating 不能为空");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence 必须在 0 到 1 之间");
        }
        decision.confidence = confidence;
        decision.evidenceHash = required(evidenceHash, "evidenceHash");
        decision.summary = required(summary, "summary");
        decision.graphVersion = graphVersion == null ? "" : graphVersion;
        decision.createdAt = LocalDateTime.now();
        decision.updatedAt = decision.createdAt;
        return decision;
    }

    public void reviewed(Map<Integer, Outcome> newOutcomes, String reflection) {
        if (newOutcomes != null) {
            newOutcomes.forEach(outcomes::putIfAbsent);
        }
        outcomeAvailableAt = outcomes.values().stream().map(Outcome::outcomeDate)
                .max(LocalDate::compareTo).orElse(null);
        reviewStatus = outcomes.keySet().containsAll(evaluationHorizons)
                ? ReviewStatus.COMPLETED : outcomes.isEmpty() ? ReviewStatus.PENDING : ReviewStatus.PARTIALLY_REVIEWED;
        this.reflection = reflection;
        updatedAt = LocalDateTime.now();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    public enum ReviewStatus {
        PENDING,
        PARTIALLY_REVIEWED,
        COMPLETED
    }

    public record Outcome(
            int horizonTradingDays,
            LocalDate outcomeDate,
            BigDecimal assetReturn,
            BigDecimal benchmarkReturn,
            BigDecimal relativeReturn,
            boolean benchmarkMissing
    ) {
    }
}
