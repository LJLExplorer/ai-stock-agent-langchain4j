package com.ljl.ai.research;

import com.ljl.ai.model.dto.ChatRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisContextResolverTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-09-05T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final AnalysisContextResolver resolver = new AnalysisContextResolver(FIXED_CLOCK);

    @Test
    void legacyRequestUsesCurrentDateAndStandardMode() {
        ChatRequest request = ChatRequest.builder()
                .userId("user-1")
                .sessionId("session-1")
                .message("分析一下 600519 最近表现")
                .build();

        AnalysisContext context = resolver.resolve(request, "session-1", "execution-1", "trace-1");

        assertEquals("600519", context.symbol());
        assertEquals(LocalDate.of(2026, 9, 5), context.analysisDate());
        assertEquals(AnalysisContext.ResearchMode.STANDARD, context.researchMode());
        assertEquals("execution-1", context.executionId());
        assertEquals("trace-1", context.traceId());
        assertEquals("user-1", context.userId());
        assertEquals("session-1", context.sessionId());
    }

    @Test
    void explicitDateAndDeepModeArePreserved() {
        ChatRequest request = ChatRequest.builder()
                .userId("user-2")
                .message("深度分析")
                .orderId("600519.SH")
                .analysisDate(LocalDate.of(2025, 12, 31))
                .researchMode(AnalysisContext.ResearchMode.DEEP)
                .build();

        AnalysisContext context = resolver.resolve(request, "created-session", "execution-2", "trace-2");

        assertEquals("600519.SH", context.symbol());
        assertEquals(LocalDate.of(2025, 12, 31), context.analysisDate());
        assertEquals(AnalysisContext.ResearchMode.DEEP, context.researchMode());
        assertEquals("created-session", context.sessionId());
    }

    @Test
    void futureAnalysisDateIsRejected() {
        ChatRequest request = ChatRequest.builder()
                .userId("user-3")
                .message("分析未来行情")
                .analysisDate(LocalDate.of(2026, 9, 6))
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(request, "session-3", "execution-3", "trace-3"));

        assertEquals("analysisDate 不能晚于当前日期", error.getMessage());
    }
}
