package com.ljl.ai.research;

import com.ljl.ai.agent.DeepResearchAssistant;
import com.ljl.ai.observability.InMemoryRunEventPublisher;
import com.ljl.ai.observability.RunEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void shouldCallEveryApplicableRoleOnceWithSameEvidenceViewAndParseJudgeJson() {
        DeepResearchAssistant assistant = successfulAssistant();
        EvidencePack pack = fullPack();
        DeepResearchService service = new DeepResearchService(assistant);

        ResearchConclusion conclusion = service.research(pack);

        assertThat(conclusion.rating()).isEqualTo(ResearchConclusion.Rating.NEUTRAL);
        assertThat(conclusion.confidence()).isEqualTo(0.72);
        assertThat(conclusion.evidenceIds()).containsExactly("ev-price");
        assertThat(conclusion.degraded()).isFalse();
        verify(assistant).fundamental(anyString(), anyString());
        verify(assistant).technical(anyString(), anyString());
        verify(assistant).news(anyString(), anyString());
        verify(assistant).bull(anyString(), anyString());
        verify(assistant).bear(anyString(), anyString());
        verify(assistant).risk(anyString(), anyString());
        verify(assistant).judge(anyString(), anyString());
        verify(assistant, times(1)).judge(anyString(), anyString());
    }

    @Test
    void shouldRunOnlyEvidenceScopedRolesInParallelBeforeJudge() throws Exception {
        DeepResearchAssistant assistant = successfulAssistant();
        CountDownLatch specialistsStarted = new CountDownLatch(4);
        org.mockito.stubbing.Answer<String> waitForPeers = invocation -> {
            specialistsStarted.countDown();
            if (!specialistsStarted.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("specialist roles did not run concurrently");
            }
            return invocation.getMethod().getName() + "摘要";
        };
        when(assistant.technical(anyString(), anyString())).thenAnswer(waitForPeers);
        when(assistant.bull(anyString(), anyString())).thenAnswer(waitForPeers);
        when(assistant.bear(anyString(), anyString())).thenAnswer(waitForPeers);
        when(assistant.risk(anyString(), anyString())).thenAnswer(waitForPeers);
        ExecutorService executor = Executors.newFixedThreadPool(4);

        try {
            DeepResearchService service = new DeepResearchService(assistant, null, executor);
            ResearchConclusion conclusion = service.research(pack());

            assertThat(conclusion.degraded()).isFalse();
            assertThat(service.plannedRoleCount(pack())).isEqualTo(5);
            verify(assistant, never()).fundamental(anyString(), anyString());
            verify(assistant, never()).news(anyString(), anyString());
            verify(assistant).judge(anyString(), anyString());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldPublishActualCompletionForEveryResearchRoleAndJudge() {
        DeepResearchAssistant assistant = successfulAssistant();
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();

        new DeepResearchService(assistant, events).research(contextualPack());

        assertThat(events.snapshot("exec-current"))
                .filteredOn(event -> event.eventType() == RunEvent.EventType.ROLE_STARTED)
                .extracting(RunEvent::node)
                .containsExactlyInAnyOrder("TECHNICAL", "BULL", "BEAR", "RISK", "JUDGE");
        assertThat(events.snapshot("exec-current"))
                .filteredOn(event -> event.eventType() == RunEvent.EventType.ROLE_COMPLETED)
                .extracting(RunEvent::node)
                .containsExactlyInAnyOrder("TECHNICAL", "BULL", "BEAR", "RISK", "JUDGE");
    }

    @Test
    void shouldContinueWhenOneRoleFailsAndMarkConclusionDegraded() {
        DeepResearchAssistant assistant = successfulAssistant();
        when(assistant.technical(anyString(), anyString())).thenThrow(new IllegalStateException("model unavailable"));

        ResearchConclusion conclusion = new DeepResearchService(assistant).research(pack());

        assertThat(conclusion.rating()).isEqualTo(ResearchConclusion.Rating.NEUTRAL);
        assertThat(conclusion.degraded()).isTrue();
        assertThat(conclusion.limitations()).contains("ROLE_FAILED:TECHNICAL");
        verify(assistant).bull(anyString(), anyString());
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
        assertThat(conclusion.limitations()).contains("JUDGE_MISSING_JSON_OBJECT");
        verify(assistant, times(1)).judge(anyString(), anyString());
    }

    @Test
    void shouldReportSpecificJudgeValidationReasons() {
        assertJudgeFailure("", "JUDGE_EMPTY_RESPONSE");
        assertJudgeFailure("{not-json}", "JUDGE_INVALID_JSON");
        assertJudgeFailure(judgeJson("ev-price").replace("NEUTRAL", "SIDEWAYS"),
                "JUDGE_INVALID_RATING");
        assertJudgeFailure(judgeJson("ev-price").replace("0.72", "1.2"),
                "JUDGE_INVALID_CONFIDENCE");
        assertJudgeFailure(judgeJson("ev-price").replace("2025-12-31", "not-a-date"),
                "JUDGE_INVALID_DATA_AS_OF");
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
        verify(assistant).technical(context.capture(), anyString());
        assertThat(context.getValue())
                .contains("本轮 EvidencePack", "历史复盘参考", "visible", "2025-12-30")
                .doesNotContain("future", "other-user");
    }

    @Test
    void shouldGiveRolesAuthoritativeAnalysisDateAndActualEvidenceScope() {
        DeepResearchAssistant assistant = successfulAssistant();

        new DeepResearchService(assistant).research(contextualPack());

        ArgumentCaptor<String> context = ArgumentCaptor.forClass(String.class);
        verify(assistant).technical(context.capture(), anyString());
        assertThat(context.getValue())
                .contains("分析日期=2025-12-31", "本轮证据范围=MARKET", "未出现的板块不属于本次分析范围");
        assertThat(DeepResearchAssistant.ROLE_RULES)
                .contains("风险字段只描述标的本身的投资风险")
                .contains("不得把角色输出质量或流水线问题写成标的风险");
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

    private void assertJudgeFailure(String rawJudge, String expectedReason) {
        DeepResearchAssistant assistant = successfulAssistant();
        when(assistant.judge(anyString(), anyString())).thenReturn(rawJudge);

        ResearchConclusion conclusion = new DeepResearchService(assistant).research(pack());

        assertThat(conclusion.rating()).isEqualTo(ResearchConclusion.Rating.INSUFFICIENT_DATA);
        assertThat(conclusion.degraded()).isTrue();
        assertThat(conclusion.limitations()).contains(expectedReason);
        verify(assistant, times(1)).judge(anyString(), anyString());
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

    private EvidencePack fullPack() {
        EvidencePack pack = pack();
        FinancialFact fact = pack.evidenceByType().values().iterator().next().get(0);
        return new EvidencePack(pack.context(), Map.of(
                FinancialFact.EvidenceType.FINANCIAL, List.of(fact),
                FinancialFact.EvidenceType.TECHNICAL, List.of(fact),
                FinancialFact.EvidenceType.NEWS, List.of(fact)),
                pack.missingItems(), pack.toolFailures(), pack.dataAsOf(), pack.evidenceHash(), pack.modelView());
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
