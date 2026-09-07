package com.ljl.ai.workflow;

import com.ljl.ai.observability.InMemoryRunEventPublisher;
import com.ljl.ai.observability.RunEvent;
import org.junit.jupiter.api.Test;

import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.StockAnalysisTask;

import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowRunnerTest {

    // 本组测试验证图编排；工具结果的 Schema/证据规则由 Validator/Reflector 测试负责。
    private WorkflowReflector routingReflector() {
        WorkflowResultValidator validator = mock(WorkflowResultValidator.class);
        when(validator.validate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        return new WorkflowReflector(2, validator);
    }

    @Test
    void resumesRealWorkflowAfterTransientAnswerFailureWithoutRepeatingCompletedTools() {
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        task.start();
        WorkflowTestResults.complete(task);
        ExecutionState state = ExecutionState.planned("resume-answer", "session", "分析", List.of(task));
        state.setPlan(plan());
        StockAnalysisTaskExecutor executor = mock(StockAnalysisTaskExecutor.class);
        WorkflowAnswerGenerator answer = mock(WorkflowAnswerGenerator.class);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("temporary outage");
            invocation.<ExecutionState>getArgument(0).setFinalAnswer("恢复后生成的结论");
            return null;
        }).when(answer).generate(any());

        ExecutionStateStore store = mock(ExecutionStateStore.class);
        var persistedVersion = new java.util.concurrent.atomic.AtomicLong(-1);
        List<WorkflowStatus> savedStatuses = new java.util.ArrayList<>();
        var persisted = new java.util.concurrent.atomic.AtomicReference<ExecutionState>();
        when(store.load(state.getExecutionId())).thenAnswer(invocation -> Optional.ofNullable(persisted.get())
                .map(WorkflowAgentState::copyOf));
        when(store.save(any(), anyLong())).thenAnswer(invocation -> {
            ExecutionState current = invocation.getArgument(0);
            long expectedVersion = invocation.getArgument(1);
            assertEquals(persistedVersion.get(), expectedVersion);
            assertTrue(current.getVersion() > expectedVersion);
            persistedVersion.set(current.getVersion());
            persisted.set(WorkflowAgentState.copyOf(current));
            savedStatuses.add(current.getWorkflowStatus());
            return current;
        });
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();
        WorkflowRunner runner = new WorkflowRunner(new StockAnalysisWorkflow(
                new StockAnalysisTaskNode(executor), routingReflector(), new WorkflowCritic(), answer, events),
                store, events);

        assertThrows(RuntimeException.class, () -> runner.run(state));
        assertEquals(WorkflowStatus.FAILED, savedStatuses.getLast());
        int failedCheckpoint = savedStatuses.size() - 1;
        long failedSequence = events.snapshot(state.getExecutionId()).getLast().sequence();

        ExecutionState resumed = runner.resume(state.getExecutionId());

        assertEquals(WorkflowStatus.RETRYING, savedStatuses.get(failedCheckpoint + 1));
        assertEquals(WorkflowStatus.COMPLETED, savedStatuses.getLast());
        assertEquals("恢复后生成的结论", resumed.getFinalAnswer());
        assertNull(resumed.getErrorMessage());
        assertEquals(2, attempts.get());
        assertEquals(1, task.getAttempts());
        verifyNoInteractions(executor);
        List<RunEvent> resumedEvents = events.snapshot(state.getExecutionId()).stream()
                .filter(event -> event.sequence() > failedSequence).toList();
        assertEquals(RunEvent.EventType.WORKFLOW_RETRYING, resumedEvents.getFirst().eventType());
        assertEquals(RunEvent.EventType.WORKFLOW_COMPLETED, resumedEvents.getLast().eventType());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void failedResumeMustPersistRetryBeforeExecutingOrPublishing(boolean conflict) {
        ExecutionState state = ExecutionState.planned("resume-cas", "session", "question", List.of());
        state.setGraphVersion(WorkflowRunner.GRAPH_VERSION);
        state.setPlanHash(WorkflowRunner.planHash(null));
        state.fail("temporary outage");
        long failedVersion = state.getVersion();
        RuntimeException error = conflict ? new CheckpointConflictException(state.getExecutionId(), failedVersion)
                : new IllegalStateException("database unavailable");
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        when(store.load(state.getExecutionId())).thenReturn(Optional.of(state));
        when(store.save(state, failedVersion)).thenThrow(error);
        StockAnalysisWorkflow workflow = mock(StockAnalysisWorkflow.class);
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();

        assertSame(error, assertThrows(RuntimeException.class,
                () -> new WorkflowRunner(workflow, store, events).resume(state.getExecutionId())));

        verifyNoInteractions(workflow);
        assertTrue(events.snapshot(state.getExecutionId()).isEmpty());
    }

    @Test
    void resumeRestoresSequenceBeforeWorkflowPublishesItsFirstEvent() {
        StockAnalysisWorkflow workflow = mock(StockAnalysisWorkflow.class);
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();
        ExecutionState state = ExecutionState.planned("resume-sequence", "session", "question", List.of());
        state.setGraphVersion(WorkflowRunner.GRAPH_VERSION);
        state.setPlanHash(WorkflowRunner.planHash(null));
        state.setEventSequence(42);
        when(store.load(state.getExecutionId())).thenReturn(Optional.of(state));
        doAnswer(invocation -> {
            events.publish(state.getExecutionId(), null, RunEvent.EventType.NODE_STARTED, "ANSWER", "resumed");
            state.complete();
            return state;
        }).when(workflow).run(eq(state), any());

        new WorkflowRunner(workflow, store, events).resume(state.getExecutionId());

        assertEquals(List.of(43L, 44L), events.snapshot(state.getExecutionId()).stream()
                .map(RunEvent::sequence).toList());
        assertEquals(44, state.getEventSequence());
    }

    @Test
    void resumingCompletedExecutionMustNotRunWorkflowAgain() {
        StockAnalysisWorkflow workflow = mock(StockAnalysisWorkflow.class);
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        ExecutionState state = ExecutionState.planned("completed", "session", "question", List.of());
        state.setGraphVersion(WorkflowRunner.GRAPH_VERSION);
        state.setPlanHash(WorkflowRunner.planHash(null));
        state.complete();
        when(store.load("completed")).thenReturn(Optional.of(state));

        assertEquals(state, new WorkflowRunner(workflow, store).resume("completed"));

        verify(workflow, never()).run(any(), any());
        verify(store, never()).save(any(), anyLong());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void terminalEventFailureMustNotRewriteCompletedCheckpointAsFailed(boolean failureInsideWorkflow) {
        StockAnalysisWorkflow workflow = mock(StockAnalysisWorkflow.class);
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        ExecutionState state = ExecutionState.planned("completed-event", "session", "question", List.of());
        List<WorkflowStatus> savedStatuses = new java.util.ArrayList<>();
        when(store.save(eq(state), anyLong())).thenAnswer(invocation -> {
            savedStatuses.add(state.getWorkflowStatus());
            return state;
        });
        doAnswer(invocation -> {
            state.complete();
            StockAnalysisWorkflow.CheckpointCallback callback = invocation.getArgument(1);
            callback.save(state, 0);
            if (failureInsideWorkflow) throw new IllegalStateException("node notification failed after commit");
            return state;
        }).when(workflow).run(eq(state), any());
        InMemoryRunEventPublisher publisher = new InMemoryRunEventPublisher() {
            @Override
            public RunEvent publish(String id, String trace, RunEvent.EventType type, String node, String summary) {
                if (type == RunEvent.EventType.WORKFLOW_COMPLETED) throw new IllegalStateException("event buffer full");
                return super.publish(id, trace, type, node, summary);
            }
        };

        new WorkflowRunner(workflow, store, publisher).run(state);

        assertEquals(WorkflowStatus.COMPLETED, state.getWorkflowStatus());
        assertEquals(List.of(WorkflowStatus.PLANNED, WorkflowStatus.COMPLETED), savedStatuses);
    }

    @Test
    void nonterminalWorkflowReturnMustNotBeReportedAsCompleted() {
        StockAnalysisWorkflow workflow = mock(StockAnalysisWorkflow.class);
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        InMemoryRunEventPublisher publisher = new InMemoryRunEventPublisher();
        ExecutionState state = ExecutionState.planned("unfinished", "session", "question", List.of());

        assertThrows(IllegalStateException.class, () -> new WorkflowRunner(workflow, store, publisher).run(state));

        verify(store).save(argThat(snapshot -> snapshot.getWorkflowStatus() == WorkflowStatus.FAILED), eq(0L));
        assertTrue(publisher.snapshot("unfinished").stream()
                .noneMatch(event -> event.eventType() == RunEvent.EventType.WORKFLOW_COMPLETED));
    }

    @Test
    void wrappedCheckpointConflictMustNotAttemptToWriteFailureOverNewOwner() {
        StockAnalysisWorkflow workflow = mock(StockAnalysisWorkflow.class);
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        ExecutionState state = ExecutionState.planned("wrapped-conflict", "session", "question", List.of());
        when(store.save(state, 0)).thenThrow(new java.util.concurrent.CompletionException(
                new CheckpointConflictException(state.getExecutionId(), 0)));
        doAnswer(invocation -> {
            StockAnalysisWorkflow.CheckpointCallback callback = invocation.getArgument(1);
            state.checkpointCompleted("INIT");
            callback.save(state, 0);
            return state;
        }).when(workflow).run(eq(state), any());

        assertThrows(RuntimeException.class, () -> new WorkflowRunner(workflow, store).run(state));

        verify(store).save(state, 0);
    }

    @Test
    void failureUsesLastPersistedVersionInsteadOfUncommittedInMemoryVersion() {
        StockAnalysisWorkflow workflow = mock(StockAnalysisWorkflow.class);
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();
        ExecutionState state = ExecutionState.planned("exec-failed", "session", "question", List.of());
        doAnswer(invocation -> {
            state.setVersion(7); // 工作流在失败前已修改内存，但尚未成功保存。
            throw new IllegalStateException("upstream failure");
        }).when(workflow).run(eq(state), any());
        assertThrows(IllegalStateException.class, () -> new WorkflowRunner(workflow, store, events).run(state));
        verify(store).save(argThat(snapshot -> snapshot.getWorkflowStatus() == WorkflowStatus.FAILED
                && snapshot.getVersion() == 1), eq(0L));
        assertEquals(RunEvent.EventType.WORKFLOW_FAILED, events.snapshot("exec-failed").getLast().eventType());
    }

    @Test
    void shouldNotOverwriteNewerCheckpointAfterOptimisticLockConflict() {
        StockAnalysisWorkflow workflow = mock(StockAnalysisWorkflow.class);
        ExecutionStateStore stateStore = mock(ExecutionStateStore.class);
        WorkflowRunner runner = new WorkflowRunner(workflow, stateStore);
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析贵州茅台", java.util.List.of());
        state.setPlan(plan());

        when(stateStore.save(state, -1)).thenReturn(state);
        when(stateStore.save(state, 0)).thenThrow(new CheckpointConflictException("exec-1", 0));
        doAnswer(invocation -> {
            StockAnalysisWorkflow.CheckpointCallback callback = invocation.getArgument(1);
            state.checkpointCompleted("INIT");
            callback.save(state, 0);
            return state;
        }).when(workflow).run(eq(state), any());

        assertThrows(CheckpointConflictException.class, () -> runner.run(state));
        verify(stateStore).load("exec-1");
    }

    @Test
    void shouldReplaceAcceptedPlaceholderUsingItsVersion() {
        StockAnalysisWorkflow workflow = mock(StockAnalysisWorkflow.class);
        ExecutionStateStore stateStore = mock(ExecutionStateStore.class);
        ExecutionState placeholder = ExecutionState.planned("exec-accepted", "session-1", "分析贵州茅台", List.of());
        placeholder.setUserId("user-1");
        placeholder.setVersion(3);
        ExecutionState planned = ExecutionState.planned("exec-accepted", "session-1", "分析贵州茅台", List.of());
        planned.setUserId("user-1");
        planned.setPlan(plan());
        when(stateStore.load("exec-accepted")).thenReturn(Optional.of(placeholder));
        when(stateStore.save(planned, 3)).thenReturn(planned);
        when(workflow.run(eq(planned), any())).thenAnswer(invocation -> {
            planned.setWorkflowStatus(WorkflowStatus.COMPLETED);
            return planned;
        });

        new WorkflowRunner(workflow, stateStore).run(planned);

        assertEquals(4, planned.getVersion());
        verify(stateStore).save(planned, 3);
        verify(stateStore, never()).save(planned, -1);
        verify(workflow).run(eq(planned), any());
    }

    @Test
    void shouldRejectOverwritingExistingNonPlaceholderExecution() {
        StockAnalysisWorkflow workflow = mock(StockAnalysisWorkflow.class);
        ExecutionStateStore stateStore = mock(ExecutionStateStore.class);
        ExecutionState existing = ExecutionState.planned("exec-existing", "session-1", "分析贵州茅台", List.of());
        existing.setUserId("user-1");
        existing.setPlan(plan());
        ExecutionState planned = ExecutionState.planned("exec-existing", "session-1", "分析贵州茅台", List.of());
        planned.setUserId("user-1");
        planned.setPlan(plan());
        when(stateStore.load("exec-existing")).thenReturn(Optional.of(existing));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new WorkflowRunner(workflow, stateStore).run(planned));

        assertEquals("EXECUTION_STATE_ALREADY_EXISTS", error.getMessage());
        verify(stateStore, never()).save(any(), anyLong());
        verify(workflow, never()).run(any(), any());
    }

    @Test
    void shouldPersistInitBeforeExecutingFirstTask() {
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();
        StockAnalysisTaskNode taskNode = mock(StockAnalysisTaskNode.class);
        ExecutionTask task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        doAnswer(invocation -> {
            ExecutionTask current = invocation.getArgument(1);
            current.start();
            WorkflowTestResults.complete(current);
            return null;
        }).when(taskNode).execute(any(), any());
        StockAnalysisWorkflow workflow = new StockAnalysisWorkflow(
                taskNode, routingReflector(), new WorkflowCritic(), null, events);
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        when(store.save(any(), anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
        ExecutionState state = ExecutionState.planned("exec-order", "session-1", "分析", List.of(task));
        state.setPlan(plan());

        ExecutionState result = new WorkflowRunner(workflow, store, events).run(state);

        var ordered = inOrder(store, taskNode);
        ordered.verify(store).save(state, -1);
        ordered.verify(store).save(argThat(snapshot -> "INIT".equals(snapshot.getLastCompletedNode())), eq(0L));
        ordered.verify(taskNode).execute(argThat(snapshot -> snapshot != state
                && snapshot.getExecutionId().equals(state.getExecutionId())), any());
        assertEquals("ANSWER", result.getLastCompletedNode());
        List<RunEvent> published = events.snapshot(state.getExecutionId());
        assertEquals(RunEvent.EventType.PLAN_CREATED, published.getFirst().eventType());
        assertTrue(published.getFirst().summary().contains("taskCount=1"));
        assertEquals(RunEvent.EventType.WORKFLOW_COMPLETED, published.getLast().eventType());
        assertEquals(LongStream.rangeClosed(1, published.size()).boxed().toList(),
                published.stream().map(RunEvent::sequence).toList());
        assertEquals(published.getLast().sequence(), result.getEventSequence());
        assertTrue(published.stream().anyMatch(event -> event.eventType() == RunEvent.EventType.NODE_STARTED));
        assertTrue(published.stream().anyMatch(event -> event.eventType() == RunEvent.EventType.NODE_COMPLETED));
    }

    @Test
    void shouldNotPublishAFalseTerminalStateWhenCheckpointOwnershipIsLost() {
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();
        StockAnalysisWorkflow workflow = new StockAnalysisWorkflow(
                null, routingReflector(), new WorkflowCritic(), null, events);
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        ExecutionState state = ExecutionState.planned("exec-checkpoint", "session-1", "分析", List.of());
        state.setPlan(plan());
        when(store.save(state, -1)).thenReturn(state);
        when(store.save(any(), eq(0L))).thenThrow(new CheckpointConflictException("exec-checkpoint", 0));

        assertThrows(RuntimeException.class,
                () -> new WorkflowRunner(workflow, store, events).run(state));

        List<RunEvent> published = events.snapshot(state.getExecutionId());
        assertEquals(List.of(RunEvent.EventType.PLAN_CREATED, RunEvent.EventType.NODE_STARTED),
                published.stream().map(RunEvent::eventType).toList());
        assertTrue(published.stream().noneMatch(event -> event.eventType() == RunEvent.EventType.NODE_COMPLETED));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"stock-analysis-v0", "stock-analysis-v1"})
    void shouldRejectIncompatibleGraphVersionBeforeResume(String oldVersion) {
        StockAnalysisWorkflow workflow = mock(StockAnalysisWorkflow.class);
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        ExecutionState state = ExecutionState.planned("exec-old", "session-1", "分析", List.of());
        state.setPlan(plan());
        state.setGraphVersion(oldVersion);
        state.setPlanHash(WorkflowRunner.planHash(state.getPlan()));
        when(store.load("exec-old")).thenReturn(java.util.Optional.of(state));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new WorkflowRunner(workflow, store).resume("exec-old"));

        assertTrue(error.getMessage().contains("INCOMPATIBLE_CHECKPOINT"));
        verify(workflow, never()).run(eq(state), any());
    }

    @Test
    void shouldRejectChangedPlanBeforeResume() {
        StockAnalysisWorkflow workflow = mock(StockAnalysisWorkflow.class);
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        ExecutionState state = ExecutionState.planned("exec-plan", "session-1", "分析", List.of());
        state.setPlan(plan());
        state.setGraphVersion(WorkflowRunner.GRAPH_VERSION);
        state.setPlanHash("outdated-plan-hash");
        when(store.load("exec-plan")).thenReturn(java.util.Optional.of(state));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new WorkflowRunner(workflow, store).resume("exec-plan"));

        assertTrue(error.getMessage().contains("INCOMPATIBLE_CHECKPOINT"));
        verify(workflow, never()).run(eq(state), any());
    }

    private AgentPlan plan() {
        return AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA)).build();
    }
}
