package com.ljl.ai.workflow;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.research.EvidencePackBuilder;
import com.ljl.ai.research.FinancialFact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowResultValidatorTest {
    private static final LocalDate DATE = LocalDate.of(2025, 12, 31);
    private final WorkflowReflector reflector = new WorkflowReflector(2, new WorkflowResultValidator(
            Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC)));

    @ParameterizedTest
    @EnumSource(StockAnalysisTask.class)
    void acceptsRealSchemaAndEvidenceAtHistoricalCutoff(StockAnalysisTask type) {
        assertThat(reflector.reflect(state(type)).trusted()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}", "\"正常结果\"", "not-json", ""})
    void rejectsMissingOrMalformedSchemaWithoutThrowing(String result) {
        var state = state(StockAnalysisTask.MARKET_DATA);
        task(state).setResult(result);
        assertCode(state, "SCHEMA_INVALID");
    }

    @Test
    void symbolMustMatchFieldEvenWhenExpectedSymbolAppearsElsewhere() {
        var state = state(StockAnalysisTask.MARKET_DATA);
        payload(state, object -> { object.put("symbol", "000001.SZ"); object.put("name", "600519.SH"); });
        assertCode(state, "SYMBOL_MISMATCH");
    }

    @Test
    void normalizesSymbolsButDoesNotIgnoreExplicitExchange() {
        var state = state(StockAnalysisTask.MARKET_DATA);
        payload(state, object -> object.put("symbol", "sh600519"));
        assertThat(reflector.reflect(state).trusted()).isTrue();
        payload(state, object -> object.put("symbol", "600519.SZ"));
        assertCode(state, "SYMBOL_MISMATCH");
    }

    @Test
    void rejectsPlanContextMismatch() {
        var state = state(StockAnalysisTask.MARKET_DATA);
        state.getPlan().setSymbol("000001.SZ");
        assertCode(state, "SYMBOL_MISMATCH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0", "NaN", "Infinity", "1,500", "1e999999"})
    void rejectsIllegalEvidenceNumbers(String value) {
        var state = state(StockAnalysisTask.MARKET_DATA);
        fact(state, object -> object.put("value", value));
        assertCode(state, "NUMERIC_INVALID");
    }

    @Test
    void rejectsNumericStringsAndFractionalVolumeInPayload() {
        var state = state(StockAnalysisTask.MARKET_DATA);
        payload(state, object -> object.put("price", "1500"));
        assertCode(state, "NUMERIC_INVALID");
        payload(state, object -> { object.put("price", 1500); object.put("volume", 1.5); });
        assertCode(state, "NUMERIC_INVALID");
    }

    @Test
    void rejectsMissingRequiredFinancialMetric() {
        var state = state(StockAnalysisTask.FINANCIAL_ANALYSIS);
        payload(state, object -> object.getJSONObject("metrics").remove("netProfit"));
        assertCode(state, "NUMERIC_INVALID");
    }

    @Test
    void rejectsDifferentEvidenceValueAndMissingPrice() {
        var state = state(StockAnalysisTask.MARKET_DATA);
        fact(state, object -> object.put("value", "1400"));
        assertCode(state, "EVIDENCE_MISMATCH");
        assertCode(state, "REQUIRED_METRIC_MISSING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unregisteredMetric", "close"})
    void quoteCannotIntroduceEvidenceMetricsOutsideItsSchema(String metric) {
        var state = state(StockAnalysisTask.MARKET_DATA);
        payload(state, object -> object.put(metric, 99));
        var facts = new java.util.ArrayList<>(task(state).getCurrentEvidence());
        var extra = JSON.parseObject(JSON.toJSONString(facts.getFirst()));
        extra.put("evidenceId", "ev-extra");
        extra.put("metric", metric);
        extra.put("value", "99");
        if (metric.equals("unregisteredMetric")) {
            extra.remove("unit");
            extra.remove("currency");
        }
        facts.add(extra.to(FinancialFact.class));
        task(state).setCurrentEvidence(facts);
        assertCode(state, "SCHEMA_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sourceName", "sourceUrl", "retrievedAt", "asOf", "unit", "currency", "value"})
    void rejectsMissingEvidenceFields(String field) {
        var state = state(StockAnalysisTask.MARKET_DATA);
        fact(state, object -> object.remove(field));
        assertThat(reflector.reflect(state).trusted()).isFalse();
    }

    @Test
    void rejectsInvalidSourceUrl() {
        var state = state(StockAnalysisTask.MARKET_DATA);
        fact(state, object -> object.put("sourceUrl", "file:///tmp/source"));
        assertCode(state, "SOURCE_MISSING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "REJECTED"})
    void temporalFlagMustBeVerified(String status) {
        var state = state(StockAnalysisTask.MARKET_DATA);
        fact(state, object -> object.put("temporalStatus", status));
        assertCode(state, "TIME_UNVERIFIED");
    }

    @Test
    void verifiedFlagCannotHideStalenessOrFutureDates() {
        var state = state(StockAnalysisTask.MARKET_DATA);
        fact(state, object -> object.put("asOf", DATE.minusDays(11).toString()));
        assertCode(state, "STALE_EVIDENCE");
        fact(state, object -> object.put("asOf", DATE.plusDays(1).toString()));
        assertCode(state, "FUTURE_EVIDENCE");
    }

    @Test
    void financialPublicationAfterCutoffIsRejectedEvenWithOldReportDate() {
        var state = state(StockAnalysisTask.FINANCIAL_ANALYSIS);
        fact(state, object -> object.put("publishedAt", "2026-01-01T00:00:00Z"));
        assertCode(state, "FUTURE_EVIDENCE");
    }

    @Test
    void rejectsWrongEvidenceTypeAndEmptyEvidence() {
        var state = state(StockAnalysisTask.MARKET_DATA);
        fact(state, object -> object.put("evidenceType", "NEWS"));
        assertCode(state, "EVIDENCE_TYPE_MISMATCH");
        task(state).setCurrentEvidence(List.of());
        assertCode(state, "INSUFFICIENT_EVIDENCE");
    }

    @Test
    void duplicateIdsCannotReplaceDistinctRequiredEvidence() {
        var state = state(StockAnalysisTask.TECHNICAL_ANALYSIS);
        var facts = task(state).getCurrentEvidence();
        var conflict = JSON.parseObject(JSON.toJSONString(facts.get(1)));
        conflict.put("evidenceId", facts.getFirst().evidenceId());
        var changed = new java.util.ArrayList<>(facts);
        changed.set(1, conflict.to(FinancialFact.class));
        task(state).setCurrentEvidence(changed);
        assertCode(state, "EVIDENCE_ID_CONFLICT");
        assertCode(state, "INSUFFICIENT_EVIDENCE");
    }

    @Test
    void newsPublicationOffsetsRepresentSameInstant() {
        var state = state(StockAnalysisTask.NEWS_ANALYSIS);
        var array = JSON.parseArray(task(state).getResult());
        array.getJSONObject(0).put("publishedAt", "2025-12-31T08:00:00+08:00");
        task(state).setResult(array.toJSONString());
        assertThat(reflector.reflect(state).trusted()).isTrue();
    }

    @Test
    void newsFailureWordsAreContentNotExecutionStatus() {
        var state = state(StockAnalysisTask.NEWS_ANALYSIS);
        assertThat(task(state).getResult()).contains("失败", "异常");
        assertThat(reflector.reflect(state).trusted()).isTrue();
    }

    @Test
    void rejectsInventedNewsEvidenceAndNullSourceWithoutThrowing() {
        var state = state(StockAnalysisTask.NEWS_ANALYSIS);
        fact(state, object -> { object.put("value", "不属于该结果"); object.remove("sourceUrl"); });
        assertCode(state, "EVIDENCE_MISMATCH");
        assertCode(state, "SOURCE_MISSING");
    }

    @Test
    void retryUsesOnlyCurrentAttemptAndKeepsHistoryForAudit() {
        var state = state(StockAnalysisTask.MARKET_DATA);
        var task = task(state);
        String raw = task.getResult();
        var historical = List.copyOf(task.getEvidence());
        task.retry("retry");
        task.start();
        task.complete(raw, List.of());
        assertThat(task.getEvidence()).isEqualTo(historical);
        assertCode(state, "INSUFFICIENT_EVIDENCE");
        var decision = reflector.reflect(state);
        assertThat(decision.retryTaskIds()).isEmpty();
        assertThat(new WorkflowCritic().criticize(decision).route()).isEqualTo(WorkflowCritic.Route.FAILED);
        assertThat(new EvidencePackBuilder().build(state.getAnalysisContext(), state.getTasks()).evidenceByType()).isEmpty();
    }

    @Test
    void emptyRetryCannotReusePreviousResult() {
        var state = state(StockAnalysisTask.MARKET_DATA);
        task(state).retry("retry");
        task(state).start();
        task(state).complete("");
        assertThat(task(state).getResult()).isEmpty();
        assertThat(task(state).getResultHistory()).hasSize(1);
        assertCode(state, "SCHEMA_INVALID");
    }

    @Test
    void restoredSuccessIsValidatedAndCanRecoverFromPreviousBadEvidence() {
        var state = state(StockAnalysisTask.MARKET_DATA);
        var good = task(state).getCurrentEvidence();
        fact(state, object -> object.put("sourceUrl", "invalid"));
        task(state).restoreSuccess(2, task(state).getResult(), good);
        assertThat(reflector.reflect(state).trusted()).isTrue();
        task(state).restoreSuccess(2, task(state).getResult(), List.of());
        assertCode(state, "INSUFFICIENT_EVIDENCE");
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"PLANNED", "RUNNING", "RETRYING"})
    void incompleteTaskCannotRouteToAnswer(TaskStatus status) {
        var state = state(StockAnalysisTask.MARKET_DATA);
        task(state).setStatus(status);
        assertCode(state, "TASK_INCOMPLETE");
    }

    private ExecutionState state(StockAnalysisTask type) {
        var task = ExecutionTask.pending("task", type);
        task.start();
        WorkflowTestResults.complete(task, DATE);
        var state = ExecutionState.planned("execution", "session", "分析", List.of(task));
        state.setPlan(AgentPlan.builder().symbol("600519.SH").tasks(List.of(type)).build());
        state.setAnalysisContext(new AnalysisContext("600519.SH", DATE, null, null, null, null, null));
        return state;
    }

    private ExecutionTask task(ExecutionState state) { return state.getTasks().getFirst(); }

    private void payload(ExecutionState state, Consumer<JSONObject> mutation) {
        var object = JSON.parseObject(task(state).getResult());
        mutation.accept(object);
        task(state).setResult(object.toJSONString());
    }

    private void fact(ExecutionState state, Consumer<JSONObject> mutation) {
        var facts = new java.util.ArrayList<>(task(state).getCurrentEvidence());
        var object = JSON.parseObject(JSON.toJSONString(facts.getFirst()));
        mutation.accept(object);
        facts.set(0, object.to(FinancialFact.class));
        task(state).setCurrentEvidence(facts);
    }

    private void assertCode(ExecutionState state, String code) {
        var decision = reflector.reflect(state);
        assertThat(decision.trusted()).isFalse();
        assertThat(decision.issues()).extracting(WorkflowResultValidator.ValidationIssue::code).contains(code);
    }
}
