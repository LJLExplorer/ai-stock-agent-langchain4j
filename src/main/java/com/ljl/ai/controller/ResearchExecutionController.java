package com.ljl.ai.controller;

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
        researchExecutionService.findOwned(executionId, userId)
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
        long cursor = 0;
        try {
            for (RunEvent event : snapshot) {
                send(emitter, event);
                cursor = event.sequence();
                if (isTerminal(event)) {
                    closeRequested.set(true);
                    emitter.complete();
                    return emitter;
                }
            }
        } catch (IOException exception) {
            emitter.completeWithError(exception);
            return emitter;
        }

        RunEventPublisher.Subscription active = eventPublisher.subscribeAfter(executionId, cursor, event -> {
            try {
                send(emitter, event);
                if (isTerminal(event)) {
                    unsubscribe.run();
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
        }
        return emitter;
    }

    private void send(SseEmitter emitter, RunEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(Long.toString(event.sequence()))
                .name(event.eventType().name())
                .data(event));
    }

    private boolean isTerminal(RunEvent event) {
        return event.eventType() == RunEvent.EventType.WORKFLOW_COMPLETED
                || event.eventType() == RunEvent.EventType.WORKFLOW_FAILED;
    }
}
