package com.ljl.ai.workflow;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.model.entity.StockQuote;
import com.ljl.ai.observability.InMemoryRunEventPublisher;
import com.ljl.ai.observability.RunEvent;
import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.research.EvidencePackBuilder;
import com.ljl.ai.research.FinancialFact;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class StockAnalysisTaskNodeTest {

    @Test
    void shouldPublishToolMetadataWithoutResultOrFailureBody() {
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();
        StockAnalysisTaskExecutor executor = mock(StockAnalysisTaskExecutor.class);
        doReturn(ToolResult.success("完整工具输出")).when(executor).execute(any(), any(), any(), any());
        ExecutionTask successTask = pendingTask();
        ExecutionState successState = state(successTask);
        new StockAnalysisTaskNode(executor, new EvidencePackBuilder(), null,
                new WorkflowRetryPolicy(), events).execute(successState, successTask);

        List<RunEvent> successEvents = events.snapshot(successState.getExecutionId());
        assertThat(successEvents).extracting(RunEvent::eventType)
                .containsExactly(RunEvent.EventType.TOOL_STARTED, RunEvent.EventType.TOOL_COMPLETED);
        assertThat(successEvents).allSatisfy(event -> assertThat(event.summary())
                .doesNotContain("完整工具输出").doesNotContain("分析600519"));
        assertThat(successState.getEventSequence()).isEqualTo(2);

        doReturn(ToolResult.failure("TOOL_ERROR", "数据源失败"))
                .when(executor).execute(any(), any(), any(), any());
        ExecutionTask failedTask = pendingTask();
        ExecutionState failedState = ExecutionState.planned("execution-failed", "session-tool",
                "分析600519", List.of(failedTask));
        failedState.setPlan(successState.getPlan());
        new StockAnalysisTaskNode(executor, new EvidencePackBuilder(), null,
                new WorkflowRetryPolicy(), events).execute(failedState, failedTask);

        assertThat(events.snapshot(failedState.getExecutionId())).extracting(RunEvent::eventType)
                .containsExactly(RunEvent.EventType.TOOL_STARTED, RunEvent.EventType.TOOL_FAILED);
        assertThat(events.snapshot(failedState.getExecutionId()).getLast().summary())
                .contains("errorCode=TOOL_ERROR").doesNotContain("数据源失败");
    }

    @Test
    void shouldLogToolMetadataWithoutSensitiveInputOrOutput() {
        StockAnalysisTaskExecutor executor = mock(StockAnalysisTaskExecutor.class);
        ToolResult<?> result = ToolResult.success("完整工具输出");
        doReturn(result).when(executor).execute(any(), any(), any(), any());
        ExecutionTask task = pendingTask();
        List<String> logs = executeAndCapture(new StockAnalysisTaskNode(executor), state(task), task);

        assertThat(logs).anySatisfy(message -> assertThat(message)
                .contains("tool_execution_started").contains("600519.SH")
                .doesNotContain("分析600519"));
        assertThat(logs).anySatisfy(message -> assertThat(message)
                .contains("tool_execution_finished").contains("success=true")
                .doesNotContain("完整工具输出"));
    }

    @Test
    void shouldLogBusinessToolFailure() {
        StockAnalysisTaskExecutor executor = mock(StockAnalysisTaskExecutor.class);
        doReturn(ToolResult.failure("TOOL_ERROR", "数据源失败"))
                .when(executor).execute(any(), any(), any(), any());
        ExecutionTask task = pendingTask();
        List<String> logs = executeAndCapture(new StockAnalysisTaskNode(executor), state(task), task);

        assertThat(logs).anySatisfy(message -> assertThat(message)
                .contains("tool_execution_finished").contains("TOOL_ERROR").contains("success=false")
                .doesNotContain("数据源失败"));
    }

    @Test
    void shouldLogToolException() {
        StockAnalysisTaskExecutor executor = mock(StockAnalysisTaskExecutor.class);
        doThrow(new IllegalStateException("连接超时")).when(executor).execute(any(), any(), any(), any());
        ExecutionTask task = pendingTask();
        List<String> logs = executeAndCapture(new StockAnalysisTaskNode(executor), state(task), task);

        assertThat(logs).anySatisfy(message -> assertThat(message)
                .contains("tool_execution_failed").contains("IllegalStateException")
                .doesNotContain("连接超时"));
    }

    @Test
    void shouldPersistMappedEvidenceAndRefreshEvidencePack() {
        StockAnalysisTaskExecutor executor = mock(StockAnalysisTaskExecutor.class);
        AnalysisContext context = new AnalysisContext("600519.SH", LocalDate.of(2025, 12, 31),
                AnalysisContext.ResearchMode.STANDARD, "execution-tool", "trace-tool", "user-1", "session-tool");
        StockQuote quote = StockQuote.builder().symbol("600519.SH").price(new BigDecimal("1500"))
                .timestamp(LocalDateTime.of(2025, 12, 31, 15, 0)).build();
        doReturn(ToolResult.success(quote)).when(executor)
                .executeWithContext(any(), any(), any(), any());
        ExecutionTask task = pendingTask();
        ExecutionState state = state(task);
        state.setAnalysisContext(context);

        new StockAnalysisTaskNode(executor, new EvidencePackBuilder()).execute(state, task);

        assertThat(task.getEvidence()).isNotEmpty();
        assertThat(state.getEvidencePack()).isNotNull();
        assertThat(state.getEvidencePack().context()).isSameAs(context);
        verify(executor).executeWithContext(any(), any(), any(), any());
    }

    @Test
    void shouldRestoreSucceededRecordWithoutCallingExecutor() {
        StockAnalysisTaskExecutor executor = mock(StockAnalysisTaskExecutor.class);
        ToolExecutionStore store = mock(ToolExecutionStore.class);
        ExecutionTask task = pendingTask();
        ExecutionState state = idempotentState(task);
        FinancialFact fact = marketFact();
        ToolExecutionRecord succeeded = ToolExecutionRecord.succeeded(
                state.getExecutionId(), task.getTaskId(), 1, "saved-raw", List.of(fact), Instant.now());
        when(store.find(state.getExecutionId(), task.getTaskId(), 1)).thenReturn(Optional.of(succeeded));

        new StockAnalysisTaskNode(executor, new EvidencePackBuilder(), store).execute(state, task);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getResult()).isEqualTo("saved-raw");
        assertThat(task.getEvidence()).containsExactly(fact);
        assertThat(task.getAttempts()).isEqualTo(1);
        verify(executor, never()).executeWithContext(any(), any(), any(), any());
        verify(store, never()).begin(anyString(), anyString(), anyInt());
    }

    @Test
    void shouldRetryReadOnlyToolWithNextAttemptAfterStartedRecord() {
        StockAnalysisTaskExecutor executor = mock(StockAnalysisTaskExecutor.class);
        ToolExecutionStore store = mock(ToolExecutionStore.class);
        ExecutionTask task = pendingTask();
        ExecutionState state = idempotentState(task);
        when(store.find(state.getExecutionId(), task.getTaskId(), 1)).thenReturn(Optional.of(
                ToolExecutionRecord.started(state.getExecutionId(), task.getTaskId(), 1, Instant.now())));
        when(store.begin(state.getExecutionId(), task.getTaskId(), 2)).thenReturn(
                ToolExecutionRecord.started(state.getExecutionId(), task.getTaskId(), 2, Instant.now()));
        StockQuote quote = StockQuote.builder().symbol("600519.SH").price(new BigDecimal("1500"))
                .timestamp(LocalDateTime.of(2025, 12, 31, 15, 0)).build();
        doReturn(ToolResult.success(quote)).when(executor).executeWithContext(any(), any(), any(), any());
        when(store.complete(eq(state.getExecutionId()), eq(task.getTaskId()), eq(2), anyString(), anyList()))
                .thenAnswer(invocation -> ToolExecutionRecord.succeeded(
                        state.getExecutionId(), task.getTaskId(), 2, invocation.getArgument(3),
                        invocation.getArgument(4), Instant.now()));

        new StockAnalysisTaskNode(executor, new EvidencePackBuilder(), store).execute(state, task);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getAttempts()).isEqualTo(2);
        verify(store).begin(state.getExecutionId(), task.getTaskId(), 2);
        verify(executor).executeWithContext(any(), any(), any(), any());
    }

    @Test
    void shouldNotCompleteTaskWhenToolRecordCompletionFails() {
        StockAnalysisTaskExecutor executor = mock(StockAnalysisTaskExecutor.class);
        ToolExecutionStore store = mock(ToolExecutionStore.class);
        ExecutionTask task = pendingTask();
        ExecutionState state = idempotentState(task);
        when(store.find(state.getExecutionId(), task.getTaskId(), 1)).thenReturn(Optional.empty());
        when(store.begin(state.getExecutionId(), task.getTaskId(), 1)).thenReturn(
                ToolExecutionRecord.started(state.getExecutionId(), task.getTaskId(), 1, Instant.now()));
        StockQuote quote = StockQuote.builder().symbol("600519.SH").price(new BigDecimal("1500"))
                .timestamp(LocalDateTime.of(2025, 12, 31, 15, 0)).build();
        doReturn(ToolResult.success(quote)).when(executor).executeWithContext(any(), any(), any(), any());
        when(store.complete(eq(state.getExecutionId()), eq(task.getTaskId()), eq(1), anyString(), anyList()))
                .thenThrow(new IllegalStateException("mongo unavailable"));
        when(store.fail(state.getExecutionId(), task.getTaskId(), 1, "mongo unavailable"))
                .thenReturn(new ToolExecutionRecord(ToolExecutionRecord.idOf(state.getExecutionId(), task.getTaskId(), 1),
                        state.getExecutionId(), task.getTaskId(), 1, ToolExecutionRecord.Status.FAILED,
                        null, List.of(), "mongo unavailable", Instant.now(), Instant.now()));

        new StockAnalysisTaskNode(executor, new EvidencePackBuilder(), store).execute(state, task);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getResult()).isNull();
    }

    private List<String> executeAndCapture(StockAnalysisTaskNode node, ExecutionState state, ExecutionTask task) {
        Logger logger = (Logger) LoggerFactory.getLogger(StockAnalysisTaskNode.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            node.execute(state, task);
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private ExecutionState state(ExecutionTask task) {
        ExecutionState state = ExecutionState.planned("execution-tool", "session-tool", "分析600519", List.of(task));
        state.setPlan(AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA)).build());
        return state;
    }

    private ExecutionState idempotentState(ExecutionTask task) {
        ExecutionState state = state(task);
        state.setAnalysisContext(new AnalysisContext("600519.SH", LocalDate.of(2025, 12, 31),
                AnalysisContext.ResearchMode.STANDARD, state.getExecutionId(), "trace-tool", "user-1",
                state.getSessionId()));
        return state;
    }

    private FinancialFact marketFact() {
        LocalDate date = LocalDate.of(2025, 12, 31);
        return new FinancialFact(FinancialFact.EvidenceType.MARKET, "price", "1500", "CNY/share", "CNY",
                date.toString(), date, date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                "provider", null, Instant.now(), null, "snapshot", FinancialFact.TemporalStatus.VERIFIED);
    }

    private ExecutionTask pendingTask() {
        return ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
    }
}
