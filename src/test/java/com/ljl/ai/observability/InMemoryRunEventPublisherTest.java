package com.ljl.ai.observability;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryRunEventPublisherTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-05T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void shouldKeepIndependentSequencesAndBoundedReplayPerExecution() {
        InMemoryRunEventPublisher publisher = new InMemoryRunEventPublisher(2, clock);

        publisher.publish("execution-1", "trace-1", RunEvent.EventType.PLAN_CREATED, "PLAN", "plan ready");
        publisher.publish("execution-1", "trace-1", RunEvent.EventType.NODE_STARTED, "INIT", "node started");
        publisher.publish("execution-1", "trace-1", RunEvent.EventType.NODE_COMPLETED, "INIT", "node completed");
        publisher.publish("execution-2", "trace-2", RunEvent.EventType.PLAN_CREATED, "PLAN", "plan ready");

        assertEquals(List.of(2L, 3L), publisher.snapshot("execution-1").stream()
                .map(RunEvent::sequence).toList());
        assertEquals(List.of(1L), publisher.snapshot("execution-2").stream()
                .map(RunEvent::sequence).toList());
        assertEquals(clock.instant(), publisher.snapshot("execution-1").getFirst().occurredAt());
    }

    @Test
    void shouldUnsubscribeListenerWithoutStoppingPublicationOrReplay() {
        InMemoryRunEventPublisher publisher = new InMemoryRunEventPublisher(4, clock);
        List<RunEvent> received = new ArrayList<>();
        RunEventPublisher.Subscription subscription = publisher.subscribe("execution-1", received::add);

        publisher.publish("execution-1", "trace-1", RunEvent.EventType.TOOL_STARTED,
                "MARKET_DATA", "tool=market_data");
        subscription.close();
        publisher.publish("execution-1", "trace-1", RunEvent.EventType.TOOL_COMPLETED,
                "MARKET_DATA", "tool=market_data,elapsedMs=12");

        assertEquals(1, received.size());
        assertEquals(2, publisher.snapshot("execution-1").size());
    }

    @Test
    void shouldExposeOnlyBoundedSummaryInsteadOfPromptOrResponsePayloadFields() {
        List<String> componentNames = Arrays.stream(RunEvent.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(String::toLowerCase)
                .toList();

        assertFalse(componentNames.stream().anyMatch(name -> name.contains("prompt")
                || name.contains("response") || name.contains("body") || name.contains("payload")));
        assertThrows(IllegalArgumentException.class, () -> new RunEvent(
                "execution-1", "trace-1", 1, clock.instant(), RunEvent.EventType.NODE_COMPLETED,
                "ANSWER", "x".repeat(RunEvent.MAX_SUMMARY_LENGTH + 1)));
    }

    @Test
    void shouldReplayEventsAfterCursorBeforeDeliveringNewEventsWithoutDuplicates() {
        InMemoryRunEventPublisher publisher = new InMemoryRunEventPublisher(5, clock);
        publisher.publish("execution-1", "trace-1", RunEvent.EventType.PLAN_CREATED, "PLAN", "ready");
        publisher.publish("execution-1", "trace-1", RunEvent.EventType.NODE_STARTED, "INIT", "started");
        List<RunEvent> received = new ArrayList<>();

        RunEventPublisher.Subscription subscription = publisher.subscribeAfter("execution-1", 1, received::add);
        publisher.publish("execution-1", "trace-1", RunEvent.EventType.NODE_COMPLETED, "INIT", "completed");
        subscription.close();
        publisher.publish("execution-1", "trace-1", RunEvent.EventType.WORKFLOW_COMPLETED, "ANSWER", "done");

        assertEquals(List.of(2L, 3L), received.stream().map(RunEvent::sequence).toList());
    }
}
