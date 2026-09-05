package com.ljl.ai.observability;

import java.util.List;
import java.util.function.Consumer;

public interface RunEventPublisher {

    RunEvent publish(String executionId, String traceId, RunEvent.EventType eventType,
                     String node, String summary);

    List<RunEvent> snapshot(String executionId);

    Subscription subscribe(String executionId, Consumer<RunEvent> listener);

    @FunctionalInterface
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
