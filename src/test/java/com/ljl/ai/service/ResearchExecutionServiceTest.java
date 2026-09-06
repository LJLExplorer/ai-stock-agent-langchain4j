package com.ljl.ai.service;

import com.ljl.ai.model.dto.ChatRequest;
import com.ljl.ai.model.dto.ChatResponse;
import com.ljl.ai.model.dto.ResearchExecutionResponse;
import com.ljl.ai.model.entity.ChatSession;
import com.ljl.ai.observability.InMemoryRunEventPublisher;
import com.ljl.ai.observability.RunEvent;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.workflow.ExecutionState;
import com.ljl.ai.workflow.ExecutionStateStore;
import com.ljl.ai.workflow.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchExecutionServiceTest {

    @Test
    void missingSessionMustBeRejectedBeforeAllocatingAnExecution() {
        ChatService chat = mock(ChatService.class);
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        doThrow(new IllegalArgumentException("会话不存在"))
                .when(chat).requireSessionForExecution("deleted-session", "user-1");
        try (ResearchExecutionService service = new ResearchExecutionService(
                chat, store, new InMemoryRunEventPublisher(), 1, 1)) {
            assertThatThrownBy(() -> service.start(deepRequest(" deleted-session ")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("会话不存在");
            org.mockito.Mockito.verifyNoInteractions(store);
            verify(chat, org.mockito.Mockito.never()).createSession(anyString(), any());
            verify(chat, org.mockito.Mockito.never()).chat(any(ChatRequest.class), anyString());
        }
    }

    @Test
    void shutdownMustPersistFailureForAcceptedTasksStillInQueue() throws Exception {
        ChatService chat = mock(ChatService.class);
        RecordingExecutionStateStore store = new RecordingExecutionStateStore();
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            running.countDown();
            release.await(5, TimeUnit.SECONDS);
            return ChatResponse.builder().success(true).build();
        }).when(chat).chat(any(ChatRequest.class), anyString());
        ResearchExecutionService service = new ResearchExecutionService(chat, store, events, 1, 2);
        try {
            service.start(deepRequest("session-1"));
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            ResearchExecutionResponse queued = service.start(deepRequest("session-2"));

            service.close();

            assertThat(store.load(queued.executionId()).orElseThrow().getWorkflowStatus())
                    .isEqualTo(WorkflowStatus.FAILED);
            assertThat(events.snapshot(queued.executionId()).getLast().eventType())
                    .isEqualTo(RunEvent.EventType.WORKFLOW_FAILED);
            verify(chat, org.mockito.Mockito.never()).chat(any(ChatRequest.class), eq(queued.executionId()));
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void rejectedAcceptanceEventMustNotLeaveBackgroundResearchRunning() {
        ChatService chat = mock(ChatService.class);
        RecordingExecutionStateStore store = new RecordingExecutionStateStore();
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher() {
            @Override
            public RunEvent publish(String executionId, String traceId, RunEvent.EventType eventType,
                                    String node, String summary) {
                if (eventType == RunEvent.EventType.EXECUTION_ACCEPTED) {
                    throw new IllegalStateException("event capacity exceeded");
                }
                return super.publish(executionId, traceId, eventType, node, summary);
            }
        };
        ResearchExecutionService service = new ResearchExecutionService(chat, store, events, 1, 2);
        try {
            assertThatThrownBy(() -> service.start(deepRequest("session-1")))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(store.statesNotIn(Set.of())).singleElement()
                    .extracting(ExecutionState::getWorkflowStatus).isEqualTo(WorkflowStatus.FAILED);
            verify(chat, org.mockito.Mockito.after(100).never()).chat(any(ChatRequest.class), anyString());
        } finally {
            service.close();
        }
    }

    @Test
    void stoppedServiceMustRejectBeforeCreatingSessionOrCheckpoint() {
        ChatService chat = mock(ChatService.class);
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        ResearchExecutionService service = new ResearchExecutionService(chat, store, new InMemoryRunEventPublisher(), 1, 1);
        service.close();

        assertThatThrownBy(() -> service.start(deepRequest(null)))
                .isInstanceOf(IllegalStateException.class).hasMessage("RESEARCH_EXECUTION_SERVICE_STOPPED");
        org.mockito.Mockito.verifyNoInteractions(chat, store);
    }

    @Test
    void shouldPreallocateExecutionAndSessionBeforeRunningDeepResearch() throws Exception {
        ChatService chatService = mock(ChatService.class);
        RecordingExecutionStateStore stateStore = new RecordingExecutionStateStore();
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();
        CountDownLatch called = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(chatService.createSession("user-1", null)).thenReturn(
                ChatSession.builder().sessionId("session-created").userId("user-1").build());
        doAnswer(invocation -> {
            called.countDown();
            release.await(2, TimeUnit.SECONDS);
            return ChatResponse.builder().success(true).sessionId("session-created").build();
        }).when(chatService).chat(any(ChatRequest.class), anyString());
        ResearchExecutionService service = new ResearchExecutionService(chatService, stateStore, events, 1, 2);

        try {
            ResearchExecutionResponse response = service.start(deepRequest(null));

            assertThat(response.executionId()).isNotBlank();
            assertThat(response.sessionId()).isEqualTo("session-created");
            assertThat(response.status()).isEqualTo(ResearchExecutionResponse.Status.ACCEPTED);
            ExecutionState accepted = service.findOwned(response.executionId(), "user-1").orElseThrow();
            assertThat(accepted.getSessionId()).isEqualTo("session-created");
            assertThat(accepted.getOriginalQuestion()).isEqualTo("深度分析 600519.SH");
            assertThat(accepted.getWorkflowStatus()).isEqualTo(WorkflowStatus.PLANNED);
            assertThat(accepted.getTasks()).isEmpty();
            assertThat(called.await(2, TimeUnit.SECONDS)).isTrue();
            ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
            verify(chatService).chat(request.capture(), eq(response.executionId()));
            assertThat(request.getValue().getSessionId()).isEqualTo("session-created");
            assertThat(events.snapshot(response.executionId())).extracting(RunEvent::eventType)
                    .startsWith(RunEvent.EventType.EXECUTION_ACCEPTED);
        } finally {
            release.countDown();
            service.close();
        }
        assertThat(service.isShutdown()).isTrue();
    }

    @Test
    void shouldPreallocateCanonicalExecutionQuestionWhenOrderIdIsSeparate() {
        ChatService chatService = mock(ChatService.class);
        RecordingExecutionStateStore stateStore = new RecordingExecutionStateStore();
        ResearchExecutionService service = new ResearchExecutionService(
                chatService, stateStore, new InMemoryRunEventPublisher(), 1, 1);
        ChatRequest request = deepRequest("session-1");
        request.setMessage("分析技术面");
        request.setOrderId("600519");

        try {
            ResearchExecutionResponse response = service.start(request);

            assertThat(service.findOwned(response.executionId(), "user-1").orElseThrow().getOriginalQuestion())
                    .isEqualTo("分析技术面\n当前用户正在咨询股票：600519");
        } finally {
            service.close();
        }
    }

    @Test
    void shouldOnlyAcceptDeepResearchMode() {
        ResearchExecutionService service = new ResearchExecutionService(
                mock(ChatService.class), mock(ExecutionStateStore.class),
                new InMemoryRunEventPublisher(), 1, 1);
        ChatRequest request = deepRequest("session-1");
        request.setResearchMode(AnalysisContext.ResearchMode.STANDARD);

        try {
            assertThatThrownBy(() -> service.start(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("DEEP_RESEARCH_MODE_REQUIRED");
        } finally {
            service.close();
        }
    }

    @Test
    void shouldRejectWithStableErrorWhenBoundedQueueIsFull() throws Exception {
        ChatService chatService = mock(ChatService.class);
        RecordingExecutionStateStore stateStore = new RecordingExecutionStateStore();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            running.countDown();
            release.await(3, TimeUnit.SECONDS);
            return ChatResponse.builder().success(true).build();
        }).when(chatService).chat(any(ChatRequest.class), anyString());
        ResearchExecutionService service = new ResearchExecutionService(
                chatService, stateStore, new InMemoryRunEventPublisher(), 1, 1);

        try {
            service.start(deepRequest("session-1"));
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            service.start(deepRequest("session-1"));
            Set<String> acceptedIds = stateStore.executionIds();

            assertThatThrownBy(() -> service.start(deepRequest("session-1")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("RESEARCH_EXECUTION_QUEUE_FULL");
            assertThat(stateStore.executionIds()).hasSize(acceptedIds.size() + 1);
            assertThat(stateStore.statesNotIn(acceptedIds))
                    .singleElement()
                    .extracting(ExecutionState::getWorkflowStatus)
                    .isEqualTo(WorkflowStatus.FAILED);
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void shouldPersistAndPublishFailureWhenAsyncInvocationThrows() {
        ChatService chatService = mock(ChatService.class);
        ExecutionStateStore stateStore = mock(ExecutionStateStore.class);
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();
        doThrow(new IllegalStateException("sensitive failure body"))
                .when(chatService).chat(any(ChatRequest.class), anyString());
        AtomicReference<ExecutionState> failedState = new AtomicReference<>();
        when(stateStore.load(anyString())).thenAnswer(invocation -> {
            ExecutionState state = ExecutionState.planned(
                    invocation.getArgument(0), "session-1", "分析", List.of());
            failedState.set(state);
            return Optional.of(state);
        });
        when(stateStore.save(any(), eq(0L))).thenAnswer(invocation -> invocation.getArgument(0));
        ResearchExecutionService service = new ResearchExecutionService(chatService, stateStore, events, 1, 1);

        try {
            ResearchExecutionResponse response = service.start(deepRequest("session-1"));
            verify(stateStore, timeout(2000)).save(any(ExecutionState.class), eq(1L));

            ExecutionState failed = failedState.get();
            assertThat(failed.getExecutionId()).isEqualTo(response.executionId());
            assertThat(failed.getWorkflowStatus().name()).isEqualTo("FAILED");
            assertThat(events.snapshot(response.executionId()).getLast().eventType())
                    .isEqualTo(RunEvent.EventType.WORKFLOW_FAILED);
            assertThat(events.snapshot(response.executionId()).getLast().summary())
                    .doesNotContain("sensitive failure body");
            assertThat(failed.getEventSequence())
                    .isEqualTo(events.snapshot(response.executionId()).getLast().sequence());
        } finally {
            service.close();
        }
    }

    private ChatRequest deepRequest(String sessionId) {
        return ChatRequest.builder()
                .sessionId(sessionId)
                .userId("user-1")
                .message("深度分析 600519.SH")
                .researchMode(AnalysisContext.ResearchMode.DEEP)
                .enableRag(true)
                .enableTools(true)
                .build();
    }

    private static final class RecordingExecutionStateStore implements ExecutionStateStore {
        private final ConcurrentHashMap<String, ExecutionState> states = new ConcurrentHashMap<>();

        @Override
        public Optional<ExecutionState> load(String executionId) {
            return Optional.ofNullable(states.get(executionId));
        }

        @Override
        public ExecutionState save(ExecutionState state, long expectedVersion) {
            states.put(state.getExecutionId(), state);
            return state;
        }

        Set<String> executionIds() {
            return Set.copyOf(states.keySet());
        }

        List<ExecutionState> statesNotIn(Set<String> executionIds) {
            return states.entrySet().stream()
                    .filter(entry -> !executionIds.contains(entry.getKey()))
                    .map(java.util.Map.Entry::getValue)
                    .toList();
        }
    }
}
