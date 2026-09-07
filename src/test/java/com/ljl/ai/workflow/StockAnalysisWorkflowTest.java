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

    // 本组测试验证图编排；工具结果的 Schema/证据规则由 Validator/Reflector 测试负责。
    private WorkflowReflector routingReflector() {
        WorkflowResultValidator validator = mock(WorkflowResultValidator.class);
        when(validator.validate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        return new WorkflowReflector(2, validator);
    }

    @Test
    void shouldReuseDurableToolResultsAfterJoinCheckpointFailure() {
        StockAnalysisTaskExecutor executor = mock(StockAnalysisTaskExecutor.class);
        ToolExecutionStore records = mock(ToolExecutionStore.class);
        ExecutionState input = ExecutionState.planned("join-resume", "session", "分析", List.of(
                ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA)));
        when(records.find("join-resume", "market", 1)).thenReturn(java.util.Optional.of(
                ToolExecutionRecord.succeeded("join-resume", "market", 1,
                        "股票：600519.SH；价格：1500", List.of(), Instant.now())));
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();
        StockAnalysisWorkflow workflow = new StockAnalysisWorkflow(
                new StockAnalysisTaskNode(executor, new com.ljl.ai.research.EvidencePackBuilder(), records),
                routingReflector(), new WorkflowCritic(), null, events);
        List<ExecutionState> durable = new ArrayList<>();

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> workflow.run(input, (snapshot, expected) -> {
            if ("TASKS_JOIN".equals(snapshot.getLastCompletedNode())) throw new IllegalStateException("database unavailable");
            durable.add(snapshot);
        }));

        assertThat(durable).hasSize(1);
        assertThat(durable.getFirst().getNextNode()).isEqualTo("DISPATCH");
        assertThat(durable.getFirst().getTasks().getFirst().getStatus()).isEqualTo(TaskStatus.PLANNED);
        assertThat(events.snapshot(input.getExecutionId())).filteredOn(event -> event.eventType() == RunEvent.EventType.NODE_COMPLETED)
                .extracting(RunEvent::node).containsExactly("INIT");

        ExecutionState resumed = workflow.run(durable.getFirst());

        assertThat(resumed.getWorkflowStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(resumed.getTasks().getFirst().getAttempts()).isEqualTo(1);
        assertThat(resumed.getTasks().getFirst().getResultHistory()).containsExactly("股票：600519.SH；价格：1500");
        org.mockito.Mockito.verifyNoInteractions(executor);
        verify(records, org.mockito.Mockito.times(2)).find("join-resume", "market", 1);
    }

    @Test
    void shouldExecuteFourBranchesConcurrentlyAndMergeAllTaskResults() {
        var arrived = new java.util.concurrent.CountDownLatch(4);
        StockAnalysisTaskNode tasks = mock(StockAnalysisTaskNode.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            ExecutionTask task = invocation.getArgument(1);
            arrived.countDown();
            org.junit.jupiter.api.Assertions.assertTrue(arrived.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "四个工具必须同时进入，不能串行执行");
            task.start();
            task.complete("股票：600519.SH；结果：" + task.getTaskType());
            return null;
        }).when(tasks).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        ExecutionState input = ExecutionState.planned("parallel", "session", "分析", java.util.Arrays.stream(StockAnalysisTask.values())
                .map(type -> ExecutionTask.pending(type.name(), type)).toList());
        input.setPlan(AgentPlan.builder().symbol("600519.SH").tasks(List.of(StockAnalysisTask.values())).build());
        List<ExecutionState> checkpoints = new ArrayList<>();

        ExecutionState result = new StockAnalysisWorkflow(tasks, routingReflector(), new WorkflowCritic(), null)
                .run(input, (snapshot, expected) -> checkpoints.add(snapshot));

        assertThat(result.getWorkflowStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(result.getTasks()).hasSize(4).allSatisfy(task -> {
            assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(task.getAttempts()).isEqualTo(1);
            assertThat(task.getResult()).contains(task.getTaskType().name());
        });
        assertThat(input.getTasks()).allSatisfy(task -> assertThat(task.getStatus()).isEqualTo(TaskStatus.PLANNED));
        assertThat(checkpoints.get(1).getLastCompletedNode()).isEqualTo("TASKS_JOIN");
        assertThat(checkpoints.get(1).getTasks()).allSatisfy(task -> assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED));
        assertThat(checkpoints.getFirst().getTasks()).allSatisfy(task -> assertThat(task.getStatus()).isEqualTo(TaskStatus.PLANNED));
    }

    @Test
    void shouldRetryOnlyFailedBranchAndPersistDecisionsAndRetryCount() {
        StockAnalysisTaskNode tasks = mock(StockAnalysisTaskNode.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            ExecutionTask task = invocation.getArgument(1);
            if (task.getStatus() == TaskStatus.COMPLETED) return null;
            task.start();
            if (task.getTaskType() == StockAnalysisTask.NEWS_ANALYSIS && task.getAttempts() == 1) task.fail("temporary");
            else WorkflowTestResults.complete(task);
            return null;
        }).when(tasks).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        ExecutionState input = ExecutionState.planned("retry-graph", "session", "分析", List.of(
                ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA),
                ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS)));
        List<ExecutionState> checkpoints = new ArrayList<>();

        ExecutionState result = new StockAnalysisWorkflow(tasks, routingReflector(), new WorkflowCritic(), null)
                .run(input, (snapshot, expected) -> checkpoints.add(snapshot));

        assertThat(result.getWorkflowStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(result.getRetryCount()).isEqualTo(1);
        assertThat(result.getTasks()).extracting(ExecutionTask::getAttempts).containsExactly(1, 2);
        assertThat(checkpoints).anySatisfy(snapshot -> {
            assertThat(snapshot.getLastCompletedNode()).isEqualTo("CRITIC");
            assertThat(snapshot.getNextNode()).isEqualTo("RETRY");
            assertThat(snapshot.getReflectionDecision().retryTaskIds()).containsExactly("news");
            assertThat(snapshot.getCriticDecision().route()).isEqualTo(WorkflowCritic.Route.RETRY);
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = WorkflowStatus.class, names = {"RUNNING", "PAUSED", "RETRYING"})
    void shouldResumeAtEvidencePackAndRevalidateWithoutRepeatingTools(WorkflowStatus status) {
        ExecutionState input = researchState(AnalysisContext.ResearchMode.STANDARD);
        List<ExecutionState> checkpoints = new ArrayList<>();
        new StockAnalysisWorkflow(null, routingReflector(), new WorkflowCritic(), null).run(input, (snapshot, expected) -> checkpoints.add(snapshot));
        ExecutionState saved = checkpoints.stream().filter(snapshot -> "CRITIC".equals(snapshot.getLastCompletedNode()))
                .findFirst().orElseThrow();
        saved.setWorkflowStatus(status);
        StockAnalysisTaskNode tasks = mock(StockAnalysisTaskNode.class);
        WorkflowReflector reflector = new WorkflowReflector();
        WorkflowCritic critic = mock(WorkflowCritic.class);
        List<String> resumedNodes = new ArrayList<>();

        ExecutionState result = new StockAnalysisWorkflow(tasks, reflector, critic, null)
                .run(saved, (snapshot, expected) -> resumedNodes.add(snapshot.getLastCompletedNode()));

        assertThat(result.getWorkflowStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        org.mockito.Mockito.verifyNoInteractions(tasks, critic);
        assertThat(resumedNodes).containsExactly("EVIDENCE_PACK", "ANSWER");
        assertThat(saved.getLastCompletedNode()).isEqualTo("CRITIC");
    }

    @Test
    void shouldBuildAndRunFanOutFanInGraph() {
        StockAnalysisWorkflow workflow = new StockAnalysisWorkflow(null, routingReflector(), new WorkflowCritic(), null);
        CompiledGraph<WorkflowAgentState> graph = workflow.compile();

        assertNotNull(graph);
    }

    @Test
    void shouldReturnExecutionSnapshotFromGraphState() {
        StockAnalysisWorkflow workflow = new StockAnalysisWorkflow(null, routingReflector(), new WorkflowCritic(), null);
        ExecutionTask market = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        ExecutionTask news = ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS);
        market.start();
        WorkflowTestResults.complete(market);
        news.start();
        WorkflowTestResults.complete(news);
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
        WorkflowTestResults.complete(market);
        news.start();
        WorkflowTestResults.complete(news);
        ExecutionState state = ExecutionState.planned("execution-2", "session-1", "分析600519.SH", List.of(market, news));
        state.setPlan(AgentPlan.builder().intent("STOCK_ANALYSIS")
                .symbol("600519.SH").tasks(List.of(market.getTaskType(), news.getTaskType())).build());

        ExecutionState result = new StockAnalysisWorkflow(null, routingReflector(), new WorkflowCritic(), null).run(state);

        assertEquals("ANSWER", result.getCurrentNode());
        assertEquals(WorkflowStatus.PLANNED, state.getWorkflowStatus());
        assertThat(result.getReflectionDecision()).isNotNull();
        assertThat(result.getCriticDecision().route()).isEqualTo(WorkflowCritic.Route.ANSWER);
    }

    @Test
    void shouldLogWorkflowNodesAndDecisionRoute() {
        ExecutionTask market = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        ExecutionTask news = ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS);
        market.start();
        WorkflowTestResults.complete(market);
        news.start();
        WorkflowTestResults.complete(news);
        ExecutionState state = ExecutionState.planned("execution-trace", "session-1", "分析600519.SH", List.of(market, news));
        state.setPlan(AgentPlan.builder().intent("STOCK_ANALYSIS")
                .symbol("600519.SH").tasks(List.of(market.getTaskType(), news.getTaskType())).build());
        Logger logger = (Logger) LoggerFactory.getLogger(StockAnalysisWorkflow.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            new StockAnalysisWorkflow(null, routingReflector(), new WorkflowCritic(), null).run(state);
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
        WorkflowTestResults.complete(market);
        ExecutionState state = ExecutionState.planned("execution-answer", "session-1", "分析600519.SH", List.of(market));
        state.setPlan(AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA)).build());
        WorkflowAnswerGenerator answerGenerator = mock(WorkflowAnswerGenerator.class);

        new StockAnalysisWorkflow(null, routingReflector(), new WorkflowCritic(), answerGenerator).run(state);

        verify(answerGenerator).generate(org.mockito.ArgumentMatchers.argThat(snapshot ->
                snapshot != state && snapshot.getExecutionId().equals(state.getExecutionId())));
    }

    @Test
    void shouldCheckpointMergedTasksAndEverySerialNodeIncludingCriticRoute() {
        ExecutionTask market = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        market.start();
        WorkflowTestResults.complete(market);
        ExecutionState state = ExecutionState.planned("execution-checkpoint", "session-1", "分析", List.of(market));
        state.setPlan(AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA)).build());
        List<String> checkpoints = new ArrayList<>();

        ExecutionState result = new StockAnalysisWorkflow(null, routingReflector(), new WorkflowCritic(), null).run(state, (current, expectedVersion) -> {
            checkpoints.add(current.getLastCompletedNode());
            assertEquals(current.getVersion(), expectedVersion + 1);
        });

        assertThat(checkpoints).containsExactly("INIT", "TASKS_JOIN", "REFLECTOR", "CRITIC", "EVIDENCE_PACK", "ANSWER");
        assertEquals("ANSWER", result.getLastCompletedNode());
    }

    @Test
    void shouldRouteStandardResearchFromEvidencePackDirectlyToAnswer() {
        DeepResearchService deepResearchService = mock(DeepResearchService.class);
        WorkflowAnswerGenerator answerGenerator = mock(WorkflowAnswerGenerator.class);
        ExecutionState state = researchState(AnalysisContext.ResearchMode.STANDARD);
        List<String> checkpoints = new ArrayList<>();

        new StockAnalysisWorkflow(null, routingReflector(), new WorkflowCritic(), answerGenerator,
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

        ExecutionState result = new StockAnalysisWorkflow(null, routingReflector(), new WorkflowCritic(), answerGenerator,
                events, deepResearchService)
                .run(state, (current, expected) -> checkpoints.add(current.getLastCompletedNode()));

        assertThat(checkpoints).containsSubsequence("EVIDENCE_PACK", "DEEP_RESEARCH", "ANSWER");
        assertThat(result.getResearchConclusion()).isEqualTo(conclusion);
        List<String> completedNodes = events.snapshot(state.getExecutionId()).stream()
                .filter(event -> event.eventType() == RunEvent.EventType.NODE_COMPLETED)
                .map(RunEvent::node).toList();
        assertThat(completedNodes).containsSubsequence("EVIDENCE_PACK", "DEEP_RESEARCH", "ANSWER");
        verify(answerGenerator).generate(org.mockito.ArgumentMatchers.argThat(snapshot ->
                snapshot != state && snapshot.getExecutionId().equals(state.getExecutionId())));
    }

    private ExecutionState researchState(AnalysisContext.ResearchMode mode) {
        ExecutionTask market = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        market.start();
        WorkflowTestResults.complete(market, LocalDate.of(2025, 12, 31));
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
