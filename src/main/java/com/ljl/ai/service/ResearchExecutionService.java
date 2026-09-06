package com.ljl.ai.service;

import com.ljl.ai.model.dto.ChatRequest;
import com.ljl.ai.model.dto.ChatResponse;
import com.ljl.ai.model.dto.ResearchExecutionResponse;
import com.ljl.ai.model.entity.ChatSession;
import com.ljl.ai.observability.RunEvent;
import com.ljl.ai.observability.RunEventPublisher;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.workflow.ExecutionState;
import com.ljl.ai.workflow.ExecutionStateStore;
import com.ljl.ai.workflow.WorkflowStatus;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 为耗时较长的 DEEP 模式预分配句柄，并在有界后台队列中执行。 */
@Slf4j
@Service
public class ResearchExecutionService implements AutoCloseable {
    private static final int DEFAULT_WORKERS = 2;
    private static final int DEFAULT_QUEUE_CAPACITY = 32;

    private final ChatService chatService;
    private final ExecutionStateStore stateStore;
    private final RunEventPublisher eventPublisher;
    private final ThreadPoolExecutor executor;

    @Autowired
    public ResearchExecutionService(ChatService chatService, ExecutionStateStore stateStore,
                                    RunEventPublisher eventPublisher) {
        this(chatService, stateStore, eventPublisher, DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY);
    }

    ResearchExecutionService(ChatService chatService, ExecutionStateStore stateStore,
                             RunEventPublisher eventPublisher, int workers, int queueCapacity) {
        if (workers <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("workers 和 queueCapacity 必须大于 0");
        }
        this.chatService = chatService;
        this.stateStore = stateStore;
        this.eventPublisher = eventPublisher;
        this.executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new ResearchThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized ResearchExecutionResponse start(ChatRequest request) {
        if (request == null || request.getResearchMode() != AnalysisContext.ResearchMode.DEEP) {
            throw new IllegalArgumentException("DEEP_RESEARCH_MODE_REQUIRED");
        }
        if (Boolean.FALSE.equals(request.getEnableTools())) {
            throw new IllegalArgumentException("深度研究需要开启工具");
        }
        if (executor.isShutdown()) {
            throw new IllegalStateException("RESEARCH_EXECUTION_SERVICE_STOPPED");
        }
        String sessionId = ensureSession(request);
        String executionId = UUID.randomUUID().toString();
        ChatRequest executionRequest = copyForSession(request, sessionId);
        ExecutionState acceptedState = ExecutionState.planned(
                executionId, sessionId,
                ChatService.executionQuestion(request.getMessage(), request.getOrderId()), List.of());
        acceptedState.setUserId(request.getUserId());
        stateStore.save(acceptedState, -1);
        ResearchTask task = new ResearchTask(executionRequest, executionId);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException exception) {
            task.cancel("RESEARCH_EXECUTION_QUEUE_FULL");
            throw new IllegalStateException("RESEARCH_EXECUTION_QUEUE_FULL", exception);
        }

        try {
            eventPublisher.publish(executionId, null, RunEvent.EventType.EXECUTION_ACCEPTED,
                    "EXECUTION", "status=accepted");
            return new ResearchExecutionResponse(executionId, sessionId,
                    ResearchExecutionResponse.Status.ACCEPTED, Instant.now());
        } catch (RuntimeException exception) {
            task.cancel("RESEARCH_ACCEPTANCE_FAILED");
            executor.remove(task);
            throw exception;
        } finally {
            task.accepted.countDown();
        }
    }

    public Optional<ExecutionState> findOwned(String executionId, String userId) {
        if (StringUtils.isBlank(executionId) || StringUtils.isBlank(userId)) {
            return Optional.empty();
        }
        return stateStore.load(executionId.trim())
                .filter(state -> userId.trim().equals(state.getUserId()));
    }

    private String ensureSession(ChatRequest request) {
        if (StringUtils.isNotBlank(request.getSessionId())) {
            String sessionId = request.getSessionId().trim();
            chatService.requireSessionForExecution(sessionId, request.getUserId());
            return sessionId;
        }
        ChatSession session = chatService.createSession(request.getUserId(), request.getOrderId());
        if (session == null || StringUtils.isBlank(session.getSessionId())) {
            throw new IllegalStateException("RESEARCH_SESSION_CREATION_FAILED");
        }
        return session.getSessionId();
    }

    private ChatRequest copyForSession(ChatRequest request, String sessionId) {
        return ChatRequest.builder()
                .sessionId(sessionId)
                .userId(request.getUserId())
                .message(request.getMessage())
                .orderId(request.getOrderId())
                .analysisDate(request.getAnalysisDate())
                .researchMode(request.getResearchMode())
                .enableRag(request.getEnableRag())
                .enableTools(request.getEnableTools())
                .build();
    }

    private void runAfterAccepted(ResearchTask task) {
        ChatRequest request = task.request;
        String executionId = task.executionId;
        try {
            task.accepted.await();
            if (task.cancelled) return;
            ChatResponse response = chatService.chat(request, executionId);
            if (response == null || !Boolean.TRUE.equals(response.getSuccess())) {
                recordFailure(request, executionId, "CHAT_EXECUTION_FAILED");
            } else {
                stateStore.load(executionId).filter(state -> state.getWorkflowStatus() != WorkflowStatus.COMPLETED
                        && state.getWorkflowStatus() != WorkflowStatus.FAILED)
                        .ifPresent(state -> recordFailure(request, executionId, "RESEARCH_WORKFLOW_NOT_COMPLETED"));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recordFailure(request, executionId, "ASYNC_EXECUTION_INTERRUPTED");
        } catch (RuntimeException exception) {
            log.error("deep_research_execution_failed executionId={}, errorType={}",
                    executionId, exception.getClass().getSimpleName());
            recordFailure(request, executionId, exception.getClass().getSimpleName());
        }
    }

    private void recordFailure(ChatRequest request, String executionId, String errorCode) {
        try {
            Optional<ExecutionState> checkpoint = stateStore.load(executionId);
            ExecutionState state = checkpoint.orElseGet(() -> {
                ExecutionState created = ExecutionState.planned(
                        executionId, request.getSessionId(), request.getMessage(), List.of());
                created.setUserId(request.getUserId());
                return created;
            });
            synchronized (state) {
                if (state.getWorkflowStatus() == WorkflowStatus.COMPLETED) {
                    return;
                }
                if (state.getWorkflowStatus() != WorkflowStatus.FAILED) {
                    long expectedVersion = checkpoint.isPresent() ? state.getVersion() : -1;
                    state.fail(errorCode);
                    stateStore.save(state, expectedVersion);
                }
            }
            RunEvent failureEvent = publishFailureIfMissing(executionId, errorCode);
            if (state.getEventSequence() < failureEvent.sequence()) {
                long expectedVersion = state.getVersion();
                state.setEventSequence(failureEvent.sequence());
                state.setVersion(expectedVersion + 1);
                stateStore.save(state, expectedVersion);
            }
        } catch (RuntimeException persistenceError) {
            log.error("deep_research_failure_checkpoint_failed executionId={}, errorType={}",
                    executionId, persistenceError.getClass().getSimpleName());
        }
    }

    private RunEvent publishFailureIfMissing(String executionId, String errorCode) {
        List<RunEvent> events = eventPublisher.snapshot(executionId);
        if (!events.isEmpty() && events.getLast().eventType() == RunEvent.EventType.WORKFLOW_FAILED) {
            return events.getLast();
        }
        return eventPublisher.publish(executionId, null, RunEvent.EventType.WORKFLOW_FAILED,
                "DEEP_RESEARCH", "errorCode=" + safeErrorCode(errorCode));
    }

    private String safeErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return "UNKNOWN";
        }
        String sanitized = errorCode.replaceAll("[^A-Za-z0-9_.-]", "_");
        return sanitized.substring(0, Math.min(64, sanitized.length()));
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        // shutdownNow 仅返回排队任务，不会调用它们的 run；显式补写终态。
        for (Runnable pending : executor.shutdownNow()) {
            if (pending instanceof ResearchTask task) {
                task.cancel("RESEARCH_EXECUTION_SERVICE_STOPPED");
            }
        }
    }

    boolean isShutdown() {
        return executor.isShutdown();
    }

    private final class ResearchTask implements Runnable {
        private final ChatRequest request;
        private final String executionId;
        private final CountDownLatch accepted = new CountDownLatch(1);
        private volatile boolean cancelled;

        private ResearchTask(ChatRequest request, String executionId) {
            this.request = request;
            this.executionId = executionId;
        }

        @Override
        public void run() {
            runAfterAccepted(this);
        }

        private void cancel(String errorCode) {
            cancelled = true;
            recordFailure(request, executionId, errorCode);
            accepted.countDown();
        }
    }

    private static final class ResearchThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "deep-research-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
