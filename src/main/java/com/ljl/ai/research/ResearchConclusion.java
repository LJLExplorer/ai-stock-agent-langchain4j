package com.ljl.ai.research;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Judge 输出经确定性校验后的不可变投研结论。 */
public record ResearchConclusion(
        Rating rating,
        double confidence,
        String summary,
        List<String> evidenceIds,
        List<String> risks,
        LocalDate dataAsOf,
        boolean degraded,
        List<String> limitations
) {
    public ResearchConclusion {
        rating = Objects.requireNonNull(rating, "rating 不能为空");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence 必须在 0 到 1 之间");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary 不能为空");
        }
        dataAsOf = Objects.requireNonNull(dataAsOf, "dataAsOf 不能为空");
        evidenceIds = evidenceIds == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(evidenceIds));
        if (evidenceIds.stream().anyMatch(id -> id == null || !id.startsWith("ev-"))) {
            throw new IllegalArgumentException("evidenceIds 必须使用 ev- 前缀");
        }
        risks = risks == null ? List.of() : List.copyOf(risks);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public enum Rating {
        BULLISH,
        NEUTRAL,
        BEARISH,
        INSUFFICIENT_DATA
    }
}
