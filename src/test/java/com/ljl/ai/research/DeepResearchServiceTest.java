package com.ljl.ai.research;

import com.ljl.ai.agent.DeepResearchAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeepResearchServiceTest {

    @Test
    void shouldValidateConclusionRatingConfidenceEvidenceIdsAndDataAsOf() {
        LocalDate date = LocalDate.of(2025, 12, 31);

        assertThatThrownBy(() -> new ResearchConclusion(null, 0.5, "结论", List.of(),
                List.of(), date, false, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResearchConclusion(ResearchConclusion.Rating.NEUTRAL, 1.1,
                "结论", List.of(), List.of(), date, false, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResearchConclusion(ResearchConclusion.Rating.NEUTRAL, 0.5,
                "结论", List.of("unknown"), List.of(), date, false, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResearchConclusion(ResearchConclusion.Rating.NEUTRAL, 0.5,
                "结论", List.of(), List.of(), null, false, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldCallEveryRoleOnceInFixedOrderWithSameEvidenceViewAndParseJudgeJson() {
        DeepResearchAssistant assistant = successfulAssistant();
        EvidencePack pack = pack();
        DeepResearchService service = new DeepResearchService(assistant);

        ResearchConclusion conclusion = service.research(pack);

        assertThat(conclusion.rating()).isEqualTo(ResearchConclusion.Rating.NEUTRAL);
        assertThat(conclusion.confidence()).isEqualTo(0.72);
        assertThat(conclusion.evidenceIds()).containsExactly("ev-price");
        assertThat(conclusion.degraded()).isFalse();
        InOrder ordered = inOrder(assistant);
        ordered.verify(assistant).fundamental(eq(pack.modelView()), anyString());
        ordered.verify(assistant).technical(eq(pack.modelView()), anyString());
        ordered.verify(assistant).news(eq(pack.modelView()), anyString());
        ordered.verify(assistant).bull(eq(pack.modelView()), anyString());
        ordered.verify(assistant).bear(eq(pack.modelView()), anyString());
        ordered.verify(assistant).risk(eq(pack.modelView()), anyString());
        ordered.verify(assistant).judge(eq(pack.modelView()), anyString());
        verify(assistant, times(1)).judge(anyString(), anyString());
    }

    @Test
    void shouldContinueWhenOneRoleFailsAndMarkConclusionDegraded() {
        DeepResearchAssistant assistant = successfulAssistant();
        when(assistant.technical(anyString(), anyString())).thenThrow(new IllegalStateException("model unavailable"));

        ResearchConclusion conclusion = new DeepResearchService(assistant).research(pack());

        assertThat(conclusion.rating()).isEqualTo(ResearchConclusion.Rating.NEUTRAL);
        assertThat(conclusion.degraded()).isTrue();
        assertThat(conclusion.limitations()).contains("ROLE_FAILED:TECHNICAL");
        verify(assistant).news(anyString(), anyString());
        verify(assistant).judge(anyString(), anyString());
    }

    @Test
    void shouldRejectJudgeEvidenceIdOutsideCurrentPack() {
        DeepResearchAssistant assistant = successfulAssistant();
        when(assistant.judge(anyString(), anyString())).thenReturn(judgeJson("ev-other"));

        ResearchConclusion conclusion = new DeepResearchService(assistant).research(pack());

        assertThat(conclusion.rating()).isEqualTo(ResearchConclusion.Rating.INSUFFICIENT_DATA);
        assertThat(conclusion.degraded()).isTrue();
        assertThat(conclusion.limitations()).contains("UNKNOWN_EVIDENCE_ID:ev-other");
    }

    @Test
    void shouldReturnDeterministicDegradedConclusionWhenJudgeFails() {
        DeepResearchAssistant assistant = successfulAssistant();
        when(assistant.judge(anyString(), anyString())).thenReturn("not-json");

        ResearchConclusion conclusion = new DeepResearchService(assistant).research(pack());

        assertThat(conclusion.rating()).isEqualTo(ResearchConclusion.Rating.INSUFFICIENT_DATA);
        assertThat(conclusion.confidence()).isZero();
        assertThat(conclusion.degraded()).isTrue();
        assertThat(conclusion.limitations()).contains("JUDGE_FAILED");
    }

    @Test
    void shouldInjectOnlySameOwnerSymbolAndHistoricallyVisibleCompletedReviewsAsReference() {
        DeepResearchAssistant assistant = successfulAssistant();
        EvidencePack pack = contextualPack();
        ResearchDecision visible = reviewedDecision("visible", "user-1", "600519.SH",
                LocalDate.of(2025, 12, 30));
        ResearchDecision future = reviewedDecision("future", "user-1", "600519.SH",
                LocalDate.of(2026, 1, 20));
        ResearchDecision otherUser = reviewedDecision("other-user", "user-2", "600519.SH",
                LocalDate.of(2025, 12, 30));

        new DeepResearchService(assistant).research(pack, List.of(visible, future, otherUser));

        ArgumentCaptor<String> context = ArgumentCaptor.forClass(String.class);
        verify(assistant).fundamental(context.capture(), anyString());
        assertThat(context.getValue())
                .contains("本轮 EvidencePack", "历史复盘参考", "visible", "2025-12-30")
                .doesNotContain("future", "other-user");
    }

    private DeepResearchAssistant successfulAssistant() {
        DeepResearchAssistant assistant = mock(DeepResearchAssistant.class);
        when(assistant.fundamental(anyString(), anyString())).thenReturn("基本面摘要");
        when(assistant.technical(anyString(), anyString())).thenReturn("技术面摘要");
        when(assistant.news(anyString(), anyString())).thenReturn("新闻摘要");
        when(assistant.bull(anyString(), anyString())).thenReturn("看多论据");
        when(assistant.bear(anyString(), anyString())).thenReturn("看空论据");
        when(assistant.risk(anyString(), anyString())).thenReturn("风险摘要");
        when(assistant.judge(anyString(), anyString())).thenReturn(judgeJson("ev-price"));
        return assistant;
    }

    private String judgeJson(String evidenceId) {
        return """
                {"rating":"NEUTRAL","confidence":0.72,"summary":"证据多空交织",\
                "evidenceIds":["%s"],"risks":["数据覆盖有限"],"dataAsOf":"2025-12-31"}
                """.formatted(evidenceId);
    }

    private EvidencePack pack() {
        LocalDate asOf = LocalDate.of(2025, 12, 31);
        FinancialFact fact = new FinancialFact("ev-price", FinancialFact.EvidenceType.MARKET,
                "close", "1500", "CNY/share", "CNY", asOf.toString(), asOf,
                asOf.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), "provider", null,
                Instant.parse("2025-12-31T15:00:00Z"), null, "snapshot-1",
                FinancialFact.TemporalStatus.VERIFIED);
        return new EvidencePack(null, Map.of(FinancialFact.EvidenceType.MARKET, List.of(fact)),
                List.of(), List.of(), Instant.parse("2025-12-31T15:00:00Z"), "hash",
                "[ev-price] MARKET close=1500 CNY/share asOf=2025-12-31 source=provider");
    }

    private EvidencePack contextualPack() {
        EvidencePack pack = pack();
        AnalysisContext context = new AnalysisContext("600519.SH", LocalDate.of(2025, 12, 31),
                AnalysisContext.ResearchMode.DEEP, "exec-current", "trace-1", "user-1", "session-1");
        return new EvidencePack(context, pack.evidenceByType(), pack.missingItems(), pack.toolFailures(),
                pack.dataAsOf(), pack.evidenceHash(), pack.modelView());
    }

    private ResearchDecision reviewedDecision(String reflection, String userId, String symbol,
                                                LocalDate availableAt) {
        ResearchDecision decision = ResearchDecision.pending("decision-" + reflection, "exec-" + reflection,
                userId, symbol, LocalDate.of(2025, 11, 1), ResearchConclusion.Rating.BULLISH,
                0.8, "hash-" + reflection, "summary", "graph-v1");
        decision.setReviewStatus(ResearchDecision.ReviewStatus.COMPLETED);
        decision.setOutcomeAvailableAt(availableAt);
        decision.setReflection(reflection);
        return decision;
    }
}
