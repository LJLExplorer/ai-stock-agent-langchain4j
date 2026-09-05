package com.ljl.ai.research;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 可追溯、可进行时点校验的不可变金融事实。
 */
public record FinancialFact(
        String evidenceId,
        EvidenceType evidenceType,
        String metric,
        String value,
        String unit,
        String currency,
        String period,
        LocalDate asOf,
        Instant publishedAt,
        String sourceName,
        String sourceUrl,
        Instant retrievedAt,
        String formula,
        String inputSnapshotId,
        TemporalStatus temporalStatus
) {
    public FinancialFact {
        evidenceType = Objects.requireNonNull(evidenceType, "evidenceType 不能为空");
        if (metric == null || metric.isBlank()) {
            throw new IllegalArgumentException("metric 不能为空");
        }
        temporalStatus = temporalStatus == null ? TemporalStatus.UNKNOWN : temporalStatus;
        evidenceId = evidenceId == null || evidenceId.isBlank()
                ? stableEvidenceId(evidenceType, metric, value, unit, currency, period, asOf,
                publishedAt, sourceName, sourceUrl, formula, inputSnapshotId)
                : evidenceId;
    }

    public FinancialFact(
            EvidenceType evidenceType,
            String metric,
            String value,
            String unit,
            String currency,
            String period,
            LocalDate asOf,
            Instant publishedAt,
            String sourceName,
            String sourceUrl,
            Instant retrievedAt,
            String formula,
            String inputSnapshotId,
            TemporalStatus temporalStatus
    ) {
        this(null, evidenceType, metric, value, unit, currency, period, asOf, publishedAt,
                sourceName, sourceUrl, retrievedAt, formula, inputSnapshotId, temporalStatus);
    }

    private static String stableEvidenceId(Object... components) {
        StringBuilder canonical = new StringBuilder();
        for (Object component : components) {
            String value = component == null ? "" : component.toString();
            canonical.append(value.length()).append(':').append(value).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "ev-" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    public enum EvidenceType {
        MARKET,
        TECHNICAL,
        FINANCIAL,
        NEWS,
        RAG
    }

    public enum TemporalStatus {
        VERIFIED,
        UNKNOWN,
        REJECTED
    }
}
