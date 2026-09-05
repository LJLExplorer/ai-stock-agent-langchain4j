package com.ljl.ai.research;

import java.time.LocalDate;

/**
 * 一次投研执行共享的不可变上下文。
 */
public record AnalysisContext(
        String symbol,
        LocalDate analysisDate,
        ResearchMode researchMode,
        String executionId,
        String traceId,
        String userId,
        String sessionId
) {
    public AnalysisContext {
        researchMode = researchMode == null ? ResearchMode.STANDARD : researchMode;
    }

    public enum ResearchMode {
        STANDARD,
        DEEP
    }
}
