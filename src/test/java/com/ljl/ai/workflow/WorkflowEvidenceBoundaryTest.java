package com.ljl.ai.workflow;

import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.research.DeepResearchService;
import com.ljl.ai.research.EvidencePack;
import com.ljl.ai.research.ResearchConclusion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowEvidenceBoundaryTest {
    private static final LocalDate DATE = LocalDate.of(2025, 12, 31);
    private final WorkflowAnswerGenerator answer = mock(WorkflowAnswerGenerator.class);
    private final DeepResearchService research = mock(DeepResearchService.class);
    private final StockAnalysisWorkflow workflow = new StockAnalysisWorkflow(null, new WorkflowReflector(),
            new WorkflowCritic(), answer, null, research);

    @ParameterizedTest
    @ValueSource(strings = {"EVIDENCE_PACK", "DEEP_RESEARCH", "ANSWER"})
    void resumingAfterReflectorCannotTrustStaleDecisionForInvalidCurrentEvidence(String nextNode) {
        var execution = state();
        execution.getTasks().getFirst().setCurrentEvidence(List.of());
        execution.setNextNode(nextNode);
        // 即便旧检查点保存了 trusted，当前证据缺失也必须在任何模型调用之前拒绝。
        execution.setReflectionDecision(new WorkflowReflector.ReflectionDecision(true, List.of(), List.of(), "trusted"));
        execution.setCriticDecision(new WorkflowCritic.Decision(WorkflowCritic.Route.ANSWER, "trusted"));

        assertThatThrownBy(() -> workflow.run(execution)).hasStackTraceContaining("UNTRUSTED_TOOL_RESULTS");
        verifyNoInteractions(answer, research);
    }

    @Test
    void answerRebuildsDerivedModelViewInsteadOfTrustingPersistedPack() {
        var execution = state();
        execution.setAnalysisContext(new AnalysisContext("600519.SH", DATE, AnalysisContext.ResearchMode.STANDARD,
                "boundary", null, null, "session"));
        execution.setNextNode("ANSWER");
        doAnswer(invocation -> {
            ExecutionState local = invocation.getArgument(0);
            assertThat(local.getEvidencePack().modelView()).contains("price=1500").doesNotContain("FORGED");
            assertThat(local.getEvidencePack().context()).isEqualTo(local.getAnalysisContext());
            local.setFinalAnswer("validated");
            return null;
        }).when(answer).generate(any());

        var result = workflow.run(execution);

        assertThat(result.getFinalAnswer()).isEqualTo("validated");
        assertThat(result.getEvidencePack().modelView()).contains("price=1500").doesNotContain("FORGED");
        assertThat(execution.getEvidencePack().modelView()).isEqualTo("FORGED price=999999");
        verify(answer).generate(any());
    }

    @Test
    void deepResearchReceivesRebuiltEvidenceAndPublishesItInDelta() {
        var execution = state();
        var graphState = WorkflowAgentState.from(execution);
        var conclusion = new ResearchConclusion(ResearchConclusion.Rating.NEUTRAL, 0.5, "summary",
                List.of(), List.of(), DATE, false, List.of());
        when(research.research(any())).thenReturn(conclusion);

        var delta = workflow.deepResearch(graphState);

        verify(research).research(argThat(pack -> pack.modelView().contains("price=1500")
                && !pack.modelView().contains("FORGED") && pack.context().equals(execution.getAnalysisContext())));
        assertThat(graphState.apply(delta).evidencePack().modelView()).contains("price=1500").doesNotContain("FORGED");
        assertThat(graphState.evidencePack().modelView()).contains("FORGED");
    }

    @Test
    void evidenceRouteBuildsMissingPackBeforeSelectingDeepResearch() {
        var execution = state();
        execution.setEvidencePack(null);
        var graphState = WorkflowAgentState.from(execution);

        var delta = workflow.evidenceRoute(graphState);

        assertThat(delta.get("nextNode")).isEqualTo("DEEP_RESEARCH");
        assertThat(graphState.apply(delta).evidencePack().modelView()).contains("price=1500");
        verifyNoInteractions(answer, research);
    }

    @Test
    void resumingAtDeepResearchRebuildsMissingPackInsteadOfSkippingResearch() {
        var execution = state();
        execution.setEvidencePack(null);

        var delta = workflow.deepResearch(WorkflowAgentState.from(execution));

        verify(research).research(argThat(pack -> pack.modelView().contains("price=1500")));
        assertThat(delta).containsKey("evidencePack");
    }

    private ExecutionState state() {
        var task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        task.start();
        WorkflowTestResults.complete(task, DATE);
        var state = ExecutionState.planned("boundary", "session", "question", List.of(task));
        state.setPlan(AgentPlan.builder().symbol("600519.SH").tasks(List.of(StockAnalysisTask.MARKET_DATA)).build());
        var context = new AnalysisContext("600519.SH", DATE, AnalysisContext.ResearchMode.DEEP,
                "boundary", null, null, "session");
        state.setAnalysisContext(context);
        state.setEvidencePack(new EvidencePack(context, Map.of(), List.of(), List.of(), null,
                "forged-hash", "FORGED price=999999"));
        return state;
    }
}
