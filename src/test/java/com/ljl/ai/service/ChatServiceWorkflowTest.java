package com.ljl.ai.service;

import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.PlanValidator;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.workflow.ExecutionState;
import com.ljl.ai.workflow.ExecutionTask;
import com.ljl.ai.workflow.WorkflowRunner;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

class ChatServiceWorkflowTest {

    @Test
    void shouldCreatePersistableExecutionStateFromValidatedPlan() {
        ChatService service = new ChatService();
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
    void shouldLogExecutionStateCreationWithTraceContext() {
        ChatService service = new ChatService();
        PlanValidator.ValidatedPlan plan = new PlanValidator.ValidatedPlan(
                true, null,
                AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                        .tasks(List.of(StockAnalysisTask.MARKET_DATA)).build(),
                List.of("getRealtimeQuote"));
        Logger logger = (Logger) LoggerFactory.getLogger(ChatService.class);
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
    void shouldExposeWorkflowTasksAsToolInvocationsAndNewsSources() {
        ExecutionTask market = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        market.start();
        market.complete("{\"symbol\":\"600519.SH\"}");
        ExecutionTask news = ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS);
        news.start();
        news.complete("[{\"title\":\"最新公告\",\"url\":\"https://example.com/news\",\"source\":\"示例财经\"}]");
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析", List.of(market, news));
        state.setPlan(AgentPlan.builder().symbol("600519.SH").tasks(List.of()).build());

        var invocations = ChatService.workflowToolInvocations(state);

        assertEquals(2, invocations.size());
        assertTrue(invocations.stream().allMatch(invocation -> Boolean.TRUE.equals(invocation.getSuccess())));
        assertEquals("https://example.com/news", ChatService.extractWebSources(invocations).getFirst().getDocumentUrl());
    }
}
