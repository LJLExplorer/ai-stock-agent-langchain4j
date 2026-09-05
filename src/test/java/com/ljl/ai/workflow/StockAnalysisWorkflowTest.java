package com.ljl.ai.workflow;

import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.observability.InMemoryRunEventPublisher;
import com.ljl.ai.observability.RunEvent;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.research.DeepResearchService;
import com.ljl.ai.research.EvidencePack;
import com.ljl.ai.research.ResearchConclusion;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.state.AgentState;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockAnalysisWorkflowTest {

    @Test
    void shouldBuildAndRunFanOutFanInGraph() {
        StockAnalysisWorkflow workflow = new StockAnalysisWorkflow();
        CompiledGraph<AgentState> graph = workflow.compile();

        assertNotNull(graph);
    }

    @Test
    void shouldRunExecutionStateWithoutPuttingItIntoGraphState() {
        StockAnalysisWorkflow workflow = new StockAnalysisWorkflow();
        ExecutionTask market = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        ExecutionTask news = ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS);
        market.start();
        market.complete("股票：600519.SH；时间：2026-08-25；价格：1500");
        news.start();
        news.complete("股票：600519.SH；时间：2026-08-25；新闻：经营稳定");
        ExecutionState executionState = ExecutionState.planned(
                "execution-1", "session-1", "分析600519.SH", List.of(market, news));
        executionState.setPlan(AgentPlan.builder().intent("STOCK_ANALYSIS")
                .symbol("600519.SH").tasks(List.of(market.getTaskType(), news.getTaskType())).build());

        ExecutionState result = workflow.run(executionState);

        assertEquals("execution-1", result.getExecutionId());
    }

    @Test
    void shouldRunReflectorAndCriticInsideTheGraph() {
        ExecutionTask market = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        ExecutionTask news = ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS);
        market.start();
        market.complete("股票：600519.SH；时间：2026-08-25；价格：1500");
        news.start();
        news.complete("股票：600519.SH；时间：2026-08-25；新闻：经营稳定");
        ExecutionState state = ExecutionState.planned("execution-2", "session-1", "分析600519.SH", List.of(market, news));
        state.setPlan(AgentPlan.builder().intent("STOCK_ANALYSIS")
                .symbol("600519.SH").tasks(List.of(market.getTaskType(), news.getTaskType())).build());

        new StockAnalysisWorkflow().run(state);

        assertEquals("ANSWER", state.getCurrentNode());
    }

    @Test
    void shouldLogWorkflowNodesAndDecisionRoute() {
        ExecutionTask market = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        ExecutionTask news = ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS);
        market.start();
        market.complete("股票：600519.SH；价格：1500");
        news.start();
        news.complete("股票：600519.SH；新闻：经营稳定");
        ExecutionState state = ExecutionState.planned("execution-trace", "session-1", "分析600519.SH", List.of(market, news));
        state.setPlan(AgentPlan.builder().intent("STOCK_ANALYSIS")
                .symbol("600519.SH").tasks(List.of(market.getTaskType(), news.getTaskType())).build());
        Logger logger = (Logger) LoggerFactory.getLogger(StockAnalysisWorkflow.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            new StockAnalysisWorkflow().run(state);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains("workflow_node_started").contains("INIT"))
                .anySatisfy(message -> assertThat(message).contains("workflow_route_selected").contains("ANSWER"));
    }

    @Test
    void shouldDelegateAnswerGenerationWithExecutionState() {
        ExecutionTask market = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        market.start();
        market.complete("股票：600519.SH；价格：1500");
        ExecutionState state = ExecutionState.planned("execution-answer", "session-1", "分析600519.SH", List.of(market));
        state.setPlan(AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA)).build());
        WorkflowAnswerGenerator answerGenerator = mock(WorkflowAnswerGenerator.class);

        new StockAnalysisWorkflow(null, new WorkflowReflector(), new WorkflowCritic(), answerGenerator).run(state);

        verify(answerGenerator).generate(state);
    }

    @Test
    void shouldCheckpointEverySuccessfulNodeIncludingCriticRoute() {
        ExecutionTask market = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        market.start();
        market.complete("股票：600519.SH；价格：1500");
        ExecutionState state = ExecutionState.planned("execution-checkpoint", "session-1", "分析", List.of(market));
        state.setPlan(AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA)).build());
        List<String> checkpoints = new ArrayList<>();

        new StockAnalysisWorkflow().run(state, (current, expectedVersion) -> {
            checkpoints.add(current.getLastCompletedNode());
            assertEquals(current.getVersion(), expectedVersion + 1);
        });

        assertThat(checkpoints).contains("INIT", "MARKET_DATA", "REFLECTOR", "CRITIC", "ANSWER");
        assertEquals("ANSWER", state.getLastCompletedNode());
    }

    @Test
    void shouldRouteStandardResearchFromEvidencePackDirectlyToAnswer() {
        DeepResearchService deepResearchService = mock(DeepResearchService.class);
        WorkflowAnswerGenerator answerGenerator = mock(WorkflowAnswerGenerator.class);
        ExecutionState state = researchState(AnalysisContext.ResearchMode.STANDARD);
        List<String> checkpoints = new ArrayList<>();

        new StockAnalysisWorkflow(null, new WorkflowReflector(), new WorkflowCritic(), answerGenerator,
                new InMemoryRunEventPublisher(), deepResearchService)
                .run(state, (current, expected) -> checkpoints.add(current.getLastCompletedNode()));

        assertThat(checkpoints).containsSubsequence("CRITIC", "EVIDENCE_PACK", "ANSWER");
        assertThat(checkpoints).doesNotContain("DEEP_RESEARCH");
        verify(deepResearchService, never()).research(state.getEvidencePack());
    }

    @Test
    void shouldCheckpointAndPublishDeepResearchNodeBeforeAnswer() {
        DeepResearchService deepResearchService = mock(DeepResearchService.class);
        ResearchConclusion conclusion = conclusion(false);
        when(deepResearchService.research(org.mockito.ArgumentMatchers.any())).thenReturn(conclusion);
        WorkflowAnswerGenerator answerGenerator = mock(WorkflowAnswerGenerator.class);
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();
        ExecutionState state = researchState(AnalysisContext.ResearchMode.DEEP);
        List<String> checkpoints = new ArrayList<>();

        new StockAnalysisWorkflow(null, new WorkflowReflector(), new WorkflowCritic(), answerGenerator,
                events, deepResearchService)
                .run(state, (current, expected) -> checkpoints.add(current.getLastCompletedNode()));

        assertThat(checkpoints).containsSubsequence("EVIDENCE_PACK", "DEEP_RESEARCH", "ANSWER");
        assertThat(state.getResearchConclusion()).isEqualTo(conclusion);
        List<String> completedNodes = events.snapshot(state.getExecutionId()).stream()
                .filter(event -> event.eventType() == RunEvent.EventType.NODE_COMPLETED)
                .map(RunEvent::node).toList();
        assertThat(completedNodes).containsSubsequence("EVIDENCE_PACK", "DEEP_RESEARCH", "ANSWER");
        verify(answerGenerator).generate(state);
    }

    private ExecutionState researchState(AnalysisContext.ResearchMode mode) {
        ExecutionTask market = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        market.start();
        market.complete("股票：600519.SH；价格：1500");
        ExecutionState state = ExecutionState.planned("execution-" + mode.name().toLowerCase(),
                "session-1", "分析600519.SH", List.of(market));
        state.setPlan(AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA)).build());
        LocalDate date = LocalDate.of(2025, 12, 31);
        AnalysisContext context = new AnalysisContext("600519.SH", date, mode, state.getExecutionId(),
                "trace-1", "user-1", state.getSessionId());
        state.setAnalysisContext(context);
        state.setEvidencePack(new EvidencePack(context, Map.of(), List.of(), List.of(),
                Instant.parse("2025-12-31T15:00:00Z"), "hash", "evidence"));
        return state;
    }

    private ResearchConclusion conclusion(boolean degraded) {
        return new ResearchConclusion(ResearchConclusion.Rating.NEUTRAL, 0.72, "多空证据交织",
                List.of("ev-price"), List.of("波动风险"), LocalDate.of(2025, 12, 31),
                degraded, degraded ? List.of("ROLE_FAILED:NEWS") : List.of());
    }
}
