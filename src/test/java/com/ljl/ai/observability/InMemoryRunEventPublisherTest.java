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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryRunEventPublisherTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-05T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void restoresCheckpointSequenceWithoutMovingAnActiveStreamBackwards() {
        InMemoryRunEventPublisher publisher = new InMemoryRunEventPublisher(4, clock);
        publisher.restoreSequence("restored", 42);
        RunEvent first = publisher.publish("restored", null, RunEvent.EventType.ROLE_STARTED, "BULL", "started");
        publisher.restoreSequence("restored", 10);
        RunEvent second = publisher.publish("restored", null, RunEvent.EventType.ROLE_COMPLETED, "BULL", "done");

        assertEquals(43, first.sequence());
        assertEquals(44, second.sequence());
        assertNotNull(first.streamId());
        assertEquals(first.streamId(), second.streamId());
        assertThrows(IllegalArgumentException.class, () -> publisher.restoreSequence("restored", -1));
    }

    @Test
    void recreatedPublisherStartsANewStreamForTheSameExecution() {
        InMemoryRunEventPublisher firstPublisher = new InMemoryRunEventPublisher(4, clock);
        RunEvent before = firstPublisher.publish("restart", null, RunEvent.EventType.ROLE_STARTED, "BULL", "started");
        InMemoryRunEventPublisher restartedPublisher = new InMemoryRunEventPublisher(4, clock);
        RunEvent after = restartedPublisher.publish("restart", null, RunEvent.EventType.ROLE_STARTED, "BULL", "started");

        assertEquals(1, after.sequence());
        assertNotEquals(before.streamId(), after.streamId());
    }

    @Test
    void evictedExecutionStartsANewStreamEvenWithoutProcessRestart() {
        InMemoryRunEventPublisher publisher = new InMemoryRunEventPublisher(4, clock);
        RunEvent before = publisher.publish("evicted", null, RunEvent.EventType.WORKFLOW_FAILED, "ANSWER", "failed");
        // 只有已完成的旧执行能被淘汰，保证填满容量时确定淘汰目标。
        for (int i = 0; i < 1024; i++) {
            publisher.publish("running-" + i, null, RunEvent.EventType.NODE_STARTED, "INIT", "started");
        }
        publisher.publish("running-0", null, RunEvent.EventType.WORKFLOW_COMPLETED, "ANSWER", "done");
        RunEvent after = publisher.publish("evicted", null, RunEvent.EventType.NODE_STARTED, "INIT", "resumed");

        assertEquals(1, after.sequence());
        assertNotEquals(before.streamId(), after.streamId());
    }

    @Test
    void cancelledEmptySubscriptionsAndInvalidEventsMustNotExhaustTheRegistry() {
        InMemoryRunEventPublisher publisher = new InMemoryRunEventPublisher(2, clock);
        for (int i = 0; i < 1100; i++) {
            String executionId = "empty-" + i;
            publisher.subscribeAfter(executionId, 0, ignored -> { }).close();
            assertThrows(NullPointerException.class, () -> publisher.publish(executionId, null, null, "", ""));
        }
        assertEquals(1, publisher.publish("valid", null, RunEvent.EventType.PLAN_CREATED, "PLAN", "ready").sequence());
    }

    @Test
    void listenerPublishingAnotherEventMustNotReorderOtherListeners() {
        InMemoryRunEventPublisher publisher = new InMemoryRunEventPublisher(10, clock);
        List<Long> received = new ArrayList<>();
        publisher.subscribe("reentrant", event -> {
            if (event.sequence() == 1) {
                publisher.publish("reentrant", null, RunEvent.EventType.ROLE_COMPLETED, "ROLE", "done");
            }
        });
        publisher.subscribeAfter("reentrant", 0, event -> received.add(event.sequence()));

        publisher.publish("reentrant", null, RunEvent.EventType.ROLE_STARTED, "ROLE", "started");

        assertEquals(List.of(1L, 2L), received);
    }

    @Test
    void liveEventsTriggeredDuringReplayMustWaitUntilReplayCompletes() {
        InMemoryRunEventPublisher publisher = new InMemoryRunEventPublisher(10, clock);
        publisher.publish("replay", null, RunEvent.EventType.PLAN_CREATED, "PLAN", "ready");
        publisher.publish("replay", null, RunEvent.EventType.NODE_STARTED, "INIT", "started");
        List<Long> received = new ArrayList<>();

        publisher.subscribeAfter("replay", 0, event -> {
            received.add(event.sequence());
            if (event.sequence() == 1) {
                publisher.publish("replay", null, RunEvent.EventType.NODE_COMPLETED, "INIT", "done");
            }
        });

        assertEquals(List.of(1L, 2L, 3L), received);
    }

    @Test
    void concurrentPublicationMustDeliverEverySequenceInOrder() throws Exception {
        InMemoryRunEventPublisher publisher = new InMemoryRunEventPublisher(1000, clock);
        List<Long> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        var subscription = publisher.subscribeAfter("parallel", 0, event -> received.add(event.sequence()));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                futures.add(executor.submit(() -> publisher.publish("parallel", null,
                        RunEvent.EventType.ROLE_COMPLETED, "ROLE", "done")));
            }
            for (var future : futures) future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            subscription.close();
        }
        List<Long> expected = java.util.stream.LongStream.rangeClosed(1, 1000).boxed().toList();
        assertEquals(expected, received);
        assertEquals(expected, publisher.snapshot("parallel").stream().map(RunEvent::sequence).toList());
    }

    @Test
    void finishedExecutionsMustBeEvictedToKeepTheRegistryBounded() {
        InMemoryRunEventPublisher publisher = new InMemoryRunEventPublisher(2, clock);
        for (int i = 0; i < 1100; i++) {
            publisher.publish("finished-" + i, null, RunEvent.EventType.WORKFLOW_COMPLETED, "ANSWER", "done");
        }
        long retained = java.util.stream.IntStream.range(0, 1100)
                .filter(i -> !publisher.snapshot("finished-" + i).isEmpty()).count();
        assertEquals(1024, retained);
    }

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
