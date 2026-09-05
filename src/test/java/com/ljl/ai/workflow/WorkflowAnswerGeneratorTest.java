package com.ljl.ai.workflow;

import com.ljl.ai.agent.WorkflowAnswerAssistant;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.research.ClaimEvidenceGuard;
import com.ljl.ai.research.EvidencePack;
import com.ljl.ai.research.FinancialFact;
import com.ljl.ai.research.ResearchConclusion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowAnswerGeneratorTest {

    private static final String MALFORMED_MONGO_SAMPLE = "### 行情 | | | | | | | | | | | | | | | | | | | | | | | | | "
            + ":--- |:--- |:--- |:--- |:--- |:--- |:--- |:--- |:--- |:--- |:--- |:--- "
            + "||||||||||||||||||||||||||||||||||||||||||||||||||||:``````````````";

    @Test
    void shouldRewriteOnceWhenFirstAnswerIsInvalid() {
        WorkflowAnswerAssistant assistant = mock(WorkflowAnswerAssistant.class);
        when(assistant.generate(anyString(), anyString())).thenReturn(MALFORMED_MONGO_SAMPLE);
        when(assistant.rewrite(anyString(), anyString(), eq("EXCESSIVE_MARKDOWN_PUNCTUATION")))
                .thenReturn("## 结论\n\n- 短期谨慎。\n- 注意波动风险。");
        WorkflowAnswerGenerator generator = generator(assistant);
        ExecutionState state = completedState();

        generator.generate(state);

        assertThat(state.getFinalAnswer()).contains("短期谨慎");
        verify(assistant, times(1)).generate(anyString(), anyString());
        verify(assistant, times(1)).rewrite(anyString(), anyString(), anyString());
    }

    @Test
    void shouldKeepValidFirstAnswerWithoutRetry() {
        WorkflowAnswerAssistant assistant = mock(WorkflowAnswerAssistant.class);
        when(assistant.generate(anyString(), anyString())).thenReturn("## 结论\n\n- 数据正常。");
        WorkflowAnswerGenerator generator = generator(assistant);
        ExecutionState state = completedState();

        generator.generate(state);

        assertThat(state.getFinalAnswer()).contains("数据正常");
        verify(assistant, never()).rewrite(anyString(), anyString(), anyString());
    }

    @Test
    void shouldUseDeterministicFallbackAfterInvalidRewrite() {
        WorkflowAnswerAssistant assistant = mock(WorkflowAnswerAssistant.class);
        when(assistant.generate(anyString(), anyString())).thenReturn(MALFORMED_MONGO_SAMPLE);
        when(assistant.rewrite(anyString(), anyString(), anyString())).thenReturn(MALFORMED_MONGO_SAMPLE);
        WorkflowAnswerGenerator generator = generator(assistant);
        ExecutionState state = completedState();

        generator.generate(state);

        assertThat(state.getFinalAnswer()).contains("MARKET_DATA", "工具明细").doesNotContain("||||");
        verify(assistant, times(1)).generate(anyString(), anyString());
        verify(assistant, times(1)).rewrite(anyString(), anyString(), anyString());
    }

    @Test
    void shouldUseFallbackWhenBothModelAttemptsThrow() {
        WorkflowAnswerAssistant assistant = mock(WorkflowAnswerAssistant.class);
        when(assistant.generate(anyString(), anyString())).thenThrow(new IllegalStateException("upstream unavailable"));
        when(assistant.rewrite(anyString(), anyString(), eq("MODEL_ERROR")))
                .thenThrow(new IllegalStateException("upstream unavailable"));
        WorkflowAnswerGenerator generator = generator(assistant);
        ExecutionState state = completedState();

        generator.generate(state);

        assertThat(state.getFinalAnswer()).isNotBlank().contains("最终摘要生成异常");
        verify(assistant, times(1)).generate(anyString(), anyString());
        verify(assistant, times(1)).rewrite(anyString(), anyString(), anyString());
    }

    @Test
    void shouldRewriteOnceWhenNumericClaimHasNoEvidenceReference() {
        WorkflowAnswerAssistant assistant = mock(WorkflowAnswerAssistant.class);
        when(assistant.generate(anyString(), anyString())).thenReturn("## 结论\n\n- 收盘价为 1500 元。");
        when(assistant.rewrite(anyString(), anyString(), eq("UNSUPPORTED_NUMERIC_CLAIM")))
                .thenReturn("## 结论\n\n- 当前证据不足，暂不提供数值结论。");
        WorkflowAnswerGenerator generator = generator(assistant);
        ExecutionState state = completedStateWithEvidence();

        generator.generate(state);

        assertThat(state.getFinalAnswer()).contains("证据不足").doesNotContain("1500");
        verify(assistant).rewrite(anyString(), anyString(), eq("UNSUPPORTED_NUMERIC_CLAIM"));
    }

    @Test
    void shouldAcceptNumericClaimReferencingCurrentEvidencePack() {
        WorkflowAnswerAssistant assistant = mock(WorkflowAnswerAssistant.class);
        when(assistant.generate(anyString(), anyString()))
                .thenReturn("## 结论\n\n- 收盘价为 1500 元。[evidence:ev-price]");
        WorkflowAnswerGenerator generator = generator(assistant);
        ExecutionState state = completedStateWithEvidence();

        generator.generate(state);

        assertThat(state.getFinalAnswer()).contains("1500", "ev-price");
        verify(assistant, never()).rewrite(anyString(), anyString(), anyString());
    }

    @Test
    void shouldPresentValidatedStructuredResearchConclusionWithoutCallingModel() {
        WorkflowAnswerAssistant assistant = mock(WorkflowAnswerAssistant.class);
        WorkflowAnswerGenerator generator = generator(assistant);
        ExecutionState state = completedStateWithEvidence();
        state.setResearchConclusion(new ResearchConclusion(ResearchConclusion.Rating.NEUTRAL, 0.72,
                "多空证据交织", List.of("ev-price"), List.of("注意波动"),
                LocalDate.of(2025, 12, 31), false, List.of()));

        generator.generate(state);

        assertThat(state.getFinalAnswer()).contains("NEUTRAL", "72%", "2025-12-31", "evidence:ev-price");
        verify(assistant, never()).generate(anyString(), anyString());
        verify(assistant, never()).rewrite(anyString(), anyString(), anyString());
    }

    @Test
    void shouldFallBackToStandardAnswerWhenJudgeConclusionIsInsufficient() {
        WorkflowAnswerAssistant assistant = mock(WorkflowAnswerAssistant.class);
        when(assistant.generate(anyString(), anyString())).thenReturn("## 结论\n\n- 当前证据不足。");
        WorkflowAnswerGenerator generator = generator(assistant);
        ExecutionState state = completedStateWithEvidence();
        state.setResearchConclusion(new ResearchConclusion(ResearchConclusion.Rating.INSUFFICIENT_DATA, 0,
                "裁决失败", List.of(), List.of(), LocalDate.of(2025, 12, 31), true,
                List.of("JUDGE_FAILED")));

        generator.generate(state);

        assertThat(state.getFinalAnswer()).contains("当前证据不足");
        verify(assistant).generate(anyString(), anyString());
    }

    private WorkflowAnswerGenerator generator(WorkflowAnswerAssistant assistant) {
        WorkflowAnswerProperties properties = new WorkflowAnswerProperties();
        properties.setMaxContextChars(1_000);
        properties.setMaxTaskChars(500);
        return new WorkflowAnswerGenerator(assistant, new AnswerContextBuilder(properties),
                new ClaimEvidenceGuard(), new AnswerQualityGuard());
    }

    private ExecutionState completedState() {
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        task.start();
        task.complete("价格：1500；趋势：震荡");
        return ExecutionState.planned("exec-1", "session-1", "分析600519.SH", List.of(task));
    }

    private ExecutionState completedStateWithEvidence() {
        ExecutionState state = completedState();
        LocalDate asOf = LocalDate.of(2025, 12, 31);
        FinancialFact fact = new FinancialFact("ev-price", FinancialFact.EvidenceType.MARKET,
                "close", "1500", "CNY/share", "CNY", asOf.toString(), asOf,
                asOf.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), "provider", null,
                Instant.parse("2025-12-31T16:00:00Z"), null, "snapshot-1",
                FinancialFact.TemporalStatus.VERIFIED);
        state.setEvidencePack(new EvidencePack(null,
                Map.of(FinancialFact.EvidenceType.MARKET, List.of(fact)), List.of(), List.of(),
                Instant.parse("2025-12-31T16:00:00Z"), "hash", "[ev-price] close=1500"));
        return state;
    }
}
