package com.ljl.ai.controller;

import com.ljl.ai.workflow.WorkflowStatus;
import java.time.Instant;

import com.ljl.ai.model.dto.ChatRequest;
import com.ljl.ai.model.dto.ResearchExecutionResponse;
import com.ljl.ai.observability.RunEvent;
import com.ljl.ai.observability.RunEventPublisher;
import com.ljl.ai.service.ResearchExecutionService;
import com.ljl.ai.workflow.ExecutionState;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/research/executions")
@RequiredArgsConstructor
public class ResearchExecutionController {
    private static final long SSE_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final ResearchExecutionService researchExecutionService;
    private final RunEventPublisher eventPublisher;

    @PostMapping
    public ResponseEntity<ResearchExecutionResponse> start(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(researchExecutionService.start(request));
    }

    @GetMapping("/{executionId}")
    public ResponseEntity<ExecutionState> status(@PathVariable String executionId,
                                                 @RequestParam String userId) {
        return ResponseEntity.of(researchExecutionService.findOwned(executionId, userId));
    }

    @GetMapping(value = "/{executionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String executionId, @RequestParam String userId) {
        ExecutionState initialState = researchExecutionService.findOwned(executionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        AtomicReference<RunEventPublisher.Subscription> subscription = new AtomicReference<>();
        AtomicBoolean closeRequested = new AtomicBoolean();
        Runnable unsubscribe = () -> {
            closeRequested.set(true);
            RunEventPublisher.Subscription current = subscription.getAndSet(null);
            if (current != null) {
                current.close();
            }
        };
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(unsubscribe);
        emitter.onError(ignored -> unsubscribe.run());

        List<RunEvent> snapshot = eventPublisher.snapshot(executionId);
        int replayStart = 0;
        for (int i = 0; i < snapshot.size(); i++) {
            RunEvent event = snapshot.get(i);
            if (isTerminal(event) && (i < snapshot.size() - 1 || !matchesTerminalState(initialState, event))) {
                // 旧尝试的终态不能关闭新尝试的订阅，也不能让前端把重试误判成失败。
                replayStart = i + 1;
            }
        }
        long cursor = replayStart == 0 ? 0 : snapshot.get(replayStart - 1).sequence();
        try {
            for (RunEvent event : snapshot.subList(replayStart, snapshot.size())) {
                send(emitter, event);
                cursor = event.sequence();
                if (isTerminal(event)) {
                    closeRequested.set(true);
                    emitter.complete();
                    return emitter;
                }
            }
            if (initialState.getWorkflowStatus() == WorkflowStatus.COMPLETED
                    || initialState.getWorkflowStatus() == WorkflowStatus.FAILED) {
                send(emitter, checkpointEvent(initialState, cursor));
                emitter.complete();
                return emitter;
            }
        } catch (IOException exception) {
            emitter.completeWithError(exception);
            return emitter;
        }

        long replayCursor = cursor;
        RunEventPublisher.Subscription active = eventPublisher.subscribeAfter(executionId, cursor, event -> {
            if (closeRequested.get()) return;
            boolean terminal = isTerminal(event);
            if (terminal && !closeRequested.compareAndSet(false, true)) return;
            try {
                if (terminal) unsubscribe.run();
                send(emitter, event);
                if (terminal) {
                    emitter.complete();
                }
            } catch (IOException exception) {
                unsubscribe.run();
                emitter.completeWithError(exception);
            }
        });
        subscription.set(active);
        if (closeRequested.get()) {
            unsubscribe.run();
        } else {
            // 进程重启或有界缓存淘汰后，以持久化终态补偿，避免空等 SSE 超时。
            try {
                researchExecutionService.findOwned(executionId, userId).ifPresent(state -> {
                    if ((state.getWorkflowStatus() == WorkflowStatus.COMPLETED
                            || state.getWorkflowStatus() == WorkflowStatus.FAILED)
                            && closeRequested.compareAndSet(false, true)) {
                        unsubscribe.run();
                        try {
                            send(emitter, checkpointEvent(state, replayCursor));
                            emitter.complete();
                        } catch (IOException exception) {
                            emitter.completeWithError(exception);
                        }
                    }
                });
            } catch (RuntimeException exception) {
                unsubscribe.run();
                emitter.completeWithError(exception);
            }
        }
        return emitter;
    }

    private void send(SseEmitter emitter, RunEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(Long.toString(event.sequence()))
                .name(event.eventType().name())
                .data(event));
    }

    private RunEvent checkpointEvent(ExecutionState state, long replayCursor) {
        return new RunEvent(state.getExecutionId(), state.getTraceId(),
                Math.addExact(Math.max(state.getEventSequence(), replayCursor), 1), Instant.now(),
                state.getWorkflowStatus() == WorkflowStatus.FAILED
                        ? RunEvent.EventType.WORKFLOW_FAILED : RunEvent.EventType.WORKFLOW_COMPLETED,
                "CHECKPOINT", "status=" + state.getWorkflowStatus(), "checkpoint:" + state.getVersion());
    }

    private boolean matchesTerminalState(ExecutionState state, RunEvent event) {
        return (state.getWorkflowStatus() == WorkflowStatus.COMPLETED
                && event.eventType() == RunEvent.EventType.WORKFLOW_COMPLETED)
                || (state.getWorkflowStatus() == WorkflowStatus.FAILED
                && event.eventType() == RunEvent.EventType.WORKFLOW_FAILED);
    }

    private boolean isTerminal(RunEvent event) {
        return event.eventType() == RunEvent.EventType.WORKFLOW_COMPLETED
                || event.eventType() == RunEvent.EventType.WORKFLOW_FAILED;
    }
}
