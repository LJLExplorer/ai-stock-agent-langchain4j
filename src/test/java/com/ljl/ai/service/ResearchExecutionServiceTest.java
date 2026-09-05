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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
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
    void shouldPreallocateExecutionAndSessionBeforeRunningDeepResearch() throws Exception {
        ChatService chatService = mock(ChatService.class);
        ExecutionStateStore stateStore = mock(ExecutionStateStore.class);
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();
        CountDownLatch called = new CountDownLatch(1);
        when(chatService.createSession("user-1", null)).thenReturn(
                ChatSession.builder().sessionId("session-created").userId("user-1").build());
        doAnswer(invocation -> {
            called.countDown();
            return ChatResponse.builder().success(true).sessionId("session-created").build();
        }).when(chatService).chat(any(ChatRequest.class), anyString());
        ResearchExecutionService service = new ResearchExecutionService(chatService, stateStore, events, 1, 2);

        try {
            ResearchExecutionResponse response = service.start(deepRequest(null));

            assertThat(response.executionId()).isNotBlank();
            assertThat(response.sessionId()).isEqualTo("session-created");
            assertThat(response.status()).isEqualTo(ResearchExecutionResponse.Status.ACCEPTED);
            assertThat(called.await(2, TimeUnit.SECONDS)).isTrue();
            ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
            verify(chatService).chat(request.capture(), eq(response.executionId()));
            assertThat(request.getValue().getSessionId()).isEqualTo("session-created");
            assertThat(events.snapshot(response.executionId())).extracting(RunEvent::eventType)
                    .startsWith(RunEvent.EventType.DEEP_RESEARCH_STARTED);
        } finally {
            service.close();
        }
        assertThat(service.isShutdown()).isTrue();
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
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            running.countDown();
            release.await(3, TimeUnit.SECONDS);
            return ChatResponse.builder().success(true).build();
        }).when(chatService).chat(any(ChatRequest.class), anyString());
        ResearchExecutionService service = new ResearchExecutionService(
                chatService, mock(ExecutionStateStore.class), new InMemoryRunEventPublisher(), 1, 1);

        try {
            service.start(deepRequest("session-1"));
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            service.start(deepRequest("session-1"));

            assertThatThrownBy(() -> service.start(deepRequest("session-1")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("RESEARCH_EXECUTION_QUEUE_FULL");
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
            verify(stateStore, timeout(2000)).save(any(ExecutionState.class), eq(0L));

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
}
