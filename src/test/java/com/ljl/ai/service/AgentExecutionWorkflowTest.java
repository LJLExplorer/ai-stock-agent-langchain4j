package com.ljl.ai.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ljl.ai.model.dto.ChatRequest;
import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.PlanValidator;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.research.AnalysisContextResolver;
import com.ljl.ai.research.DecisionReviewService;
import com.ljl.ai.research.ResearchConclusion;
import com.ljl.ai.research.ResearchDecision;
import com.ljl.ai.research.ResearchDecisionService;
import com.ljl.ai.workflow.ExecutionState;
import com.ljl.ai.workflow.ExecutionTask;
import com.ljl.ai.workflow.WorkflowStatus;
import com.ljl.ai.workflow.WorkflowRunner;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentExecutionWorkflowTest {

    @Test
    void shouldCreatePersistableExecutionStateFromValidatedPlan() {
        AgentExecutionService service = new AgentExecutionService();
        PlanValidator.ValidatedPlan plan = new PlanValidator.ValidatedPlan(
                true, null,
                AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                        .tasks(List.of(StockAnalysisTask.MARKET_DATA, StockAnalysisTask.NEWS_ANALYSIS)).build(),
                List.of("getRealtimeQuote", "searchStockNewsAndAnnouncements"));

        ExecutionState state = service.createExecutionState("user-1", "session-1", "分析贵州茅台", plan);

        assertNotNull(state.getExecutionId());
        assertEquals("600519.SH", state.getPlan().getSymbol());
        assertEquals(2, state.getTasks().size());
    }

    @Test
    void shouldUsePreallocatedExecutionIdWhenCreatingWorkflowState() {
        AgentExecutionService service = new AgentExecutionService();
        PlanValidator.ValidatedPlan plan = new PlanValidator.ValidatedPlan(
                true, null,
                AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                        .tasks(List.of(StockAnalysisTask.MARKET_DATA)).build(),
                List.of("getRealtimeQuote"));

        ExecutionState state = service.createExecutionState(
                "user-1", "session-1", "分析贵州茅台", plan, "execution-preallocated");

        assertEquals("execution-preallocated", state.getExecutionId());
    }

    @Test
    void shouldLogExecutionStateCreationWithTraceContext() {
        AgentExecutionService service = new AgentExecutionService();
        PlanValidator.ValidatedPlan plan = new PlanValidator.ValidatedPlan(
                true, null,
                AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                        .tasks(List.of(StockAnalysisTask.MARKET_DATA)).build(),
                List.of("getRealtimeQuote"));
        Logger logger = (Logger) LoggerFactory.getLogger(AgentExecutionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put("traceId", "trace-workflow-test");

        try {
            service.createExecutionState("user-1", "session-1", "分析贵州茅台", plan);
        } finally {
            MDC.clear();
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                        .contains("plan_execution_confirmed")
                        .contains("trace-workflow-test")
                        .contains("600519.SH"));
    }

    @Test
    void shouldExposeExecutionResumeEntryPoint() throws NoSuchMethodException {
        assertNotNull(WorkflowRunner.class.getMethod("resume", String.class));
    }

    @Test
    void shouldRenderControlledAnswerForFailedWorkflow() {
        AgentExecutionService service = new AgentExecutionService();
        ExecutionTask task = ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS);
        task.start();
        task.fail("新闻证据过期");
        ExecutionState state = ExecutionState.planned("execution", "session", "分析", List.of(task));
        state.setWorkflowStatus(WorkflowStatus.FAILED);
        state.setErrorMessage("STALE_EVIDENCE");

        String answer = ReflectionTestUtils.invokeMethod(service, "workflowFailureAnswer", state);

        assertThat(answer).contains("分析未完成", "STALE_EVIDENCE", "没有生成买入或卖出结论");
    }

    @Test
    void shouldResolveDeepAnalysisContextAndInjectOnlyHistoricallyVisibleReviewsBestEffort() {
        AgentExecutionService service = new AgentExecutionService();
        AnalysisContextResolver contextResolver = mock(AnalysisContextResolver.class);
        DecisionReviewService reviewService = mock(DecisionReviewService.class);
        ResearchDecisionService decisionService = mock(ResearchDecisionService.class);
        ReflectionTestUtils.setField(service, "analysisContextResolver", contextResolver);
        ReflectionTestUtils.setField(service, "decisionReviewService", reviewService);
        ReflectionTestUtils.setField(service, "researchDecisionService", decisionService);
        LocalDate asOf = LocalDate.of(2026, 2, 1);
        ChatRequest request = ChatRequest.builder().userId("user-1").message("分析贵州茅台")
                .analysisDate(asOf).researchMode(AnalysisContext.ResearchMode.DEEP).build();
        PlanValidator.ValidatedPlan plan = validatedPlan();
        AnalysisContext resolved = new AnalysisContext(null, asOf, AnalysisContext.ResearchMode.DEEP,
                "exec-1", "trace-1", "user-1", "session-1");
        ResearchDecision visible = reviewedDecision(LocalDate.of(2026, 1, 31));
        when(contextResolver.resolve(request, "session-1", "exec-1", null)).thenReturn(resolved);
        when(reviewService.reviewDue("user-1", "600519.SH", asOf))
                .thenThrow(new IllegalStateException("review unavailable"));
        when(decisionService.findCompletedReviews("user-1", "600519.SH", asOf))
                .thenReturn(List.of(visible));

        ExecutionState state = service.createExecutionState(
                request, "session-1", "分析贵州茅台", plan, "exec-1");

        assertThat(state.getAnalysisContext().researchMode()).isEqualTo(AnalysisContext.ResearchMode.DEEP);
        assertThat(state.getAnalysisContext().symbol()).isEqualTo("600519.SH");
        assertThat(state.getDecisionReviews()).containsExactly(visible);
        verify(decisionService).findCompletedReviews("user-1", "600519.SH", asOf);
    }

    private PlanValidator.ValidatedPlan validatedPlan() {
        return new PlanValidator.ValidatedPlan(true, null,
                AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                        .tasks(List.of(StockAnalysisTask.MARKET_DATA)).build(),
                List.of("getRealtimeQuote"));
    }

    private ResearchDecision reviewedDecision(LocalDate availableAt) {
        ResearchDecision decision = ResearchDecision.pending("decision-1", "old-exec", "user-1",
                "600519.SH", LocalDate.of(2025, 12, 1), ResearchConclusion.Rating.BULLISH,
                0.8, "old-hash", "历史结论", "graph-v1");
        decision.setReviewStatus(ResearchDecision.ReviewStatus.COMPLETED);
        decision.setOutcomeAvailableAt(availableAt);
        decision.setReflection("20日后相对基准收益为0.1");
        return decision;
    }

}
