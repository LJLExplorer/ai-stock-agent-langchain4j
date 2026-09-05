package com.ljl.ai.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentEvalRunnerTest {

    @Test
    void shouldCalculateStableOfflineBaselineForAllAgentLayers() {
        List<AgentEvalRunner.EvalCase> cases = AgentEvalRunner.load(
                getClass().getResourceAsStream("/eval/agent-eval-cases.json"));
        Map<String, AgentEvalRunner.Observation> observations = Map.of(
                "planner-stock", observation("STOCK_ANALYSIS:MARKET_DATA,NEWS_ANALYSIS", List.of(), 0, 0, 0, 0, 10, 1),
                "topic-switch", observation("SWITCH:300750", List.of(), 0, 0, 0, 0, 20, 1),
                "rag-financial", observation(null, List.of("doc-financial", "doc-noise", "doc-news"), 0, 0, 0, 0, 30, 1),
                "evidence-grounded", observation("PASS", List.of(), 2, 2, 1, 1, 40, 0),
                "recovery-compatible", observation("RESUMED", List.of(), 0, 0, 0, 0, 50, 0));

        AgentEvalRunner.Report report = new AgentEvalRunner().run(
                cases, evalCase -> observations.get(evalCase.caseId()), 3);

        assertThat(report.caseCount()).isEqualTo(5);
        assertThat(report.accuracy()).isEqualTo(1.0);
        assertThat(report.recallAtK()).isEqualTo(1.0);
        assertThat(report.ndcgAtK()).isEqualTo(1.0 / (1.0 + 1.0 / log2(3))
                * (1.0 + 1.0 / log2(4)));
        assertThat(report.citationCoverage()).isEqualTo(1.0);
        assertThat(report.numericConsistency()).isEqualTo(1.0);
        assertThat(report.averageLatencyMillis()).isEqualTo(30.0);
        assertThat(report.totalCalls()).isEqualTo(3);
        assertThat(report.toStableJson()).isEqualTo(
                "{\"caseCount\":5,\"accuracy\":1.0,\"recallAtK\":1.0,\"ndcgAtK\":0.9197207891481876,"
                        + "\"citationCoverage\":1.0,\"numericConsistency\":1.0,"
                        + "\"averageLatencyMillis\":30.0,\"totalCalls\":3}");
    }

    @Test
    void shouldRejectZeroCasesDuplicateIdsAndInvalidExpectations() {
        AgentEvalRunner runner = new AgentEvalRunner();
        AgentEvalRunner.EvalCase valid = new AgentEvalRunner.EvalCase(
                "case-1", AgentEvalRunner.Category.PLANNER, "input", "PASS", List.of(), 0, 0);

        assertThatThrownBy(() -> runner.run(List.of(), ignored -> null, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("至少一个");
        assertThatThrownBy(() -> runner.run(List.of(valid, valid), ignored -> null, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重复 caseId");
        assertThatThrownBy(() -> runner.run(List.of(new AgentEvalRunner.EvalCase(
                        "bad", AgentEvalRunner.Category.EVIDENCE, "input", "PASS", List.of(), -1, 0)),
                ignored -> null, 3)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("期望值");
    }

    @Test
    void shouldRejectMalformedObservationsInsteadOfHidingMetricErrors() {
        AgentEvalRunner.EvalCase evidence = new AgentEvalRunner.EvalCase(
                "evidence", AgentEvalRunner.Category.EVIDENCE, "input", "PASS", List.of(), 1, 1);
        AgentEvalRunner.Observation malformed = observation("PASS", List.of(), 1, 2, 1, 1, 1, 0);

        assertThatThrownBy(() -> new AgentEvalRunner().run(List.of(evidence), ignored -> malformed, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("观测值");
    }

    private AgentEvalRunner.Observation observation(String label, List<String> rankedIds,
                                                     int claims, int citedClaims,
                                                     int numericClaims, int consistentNumericClaims,
                                                     long latencyMillis, int calls) {
        return new AgentEvalRunner.Observation(label, rankedIds, claims, citedClaims,
                numericClaims, consistentNumericClaims, latencyMillis, calls);
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2);
    }
}
