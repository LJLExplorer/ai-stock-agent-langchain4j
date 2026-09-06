package com.ljl.ai.observability;

import java.util.List;
import java.util.function.Consumer;

public interface RunEventPublisher {

    RunEvent publish(String executionId, String traceId, RunEvent.EventType eventType,
                     String node, String summary);

    List<RunEvent> snapshot(String executionId);

    /** 恢复检查点中的序号下限，不回退当前事件流已使用的序号。 */
    void restoreSequence(String executionId, long sequence);

    Subscription subscribe(String executionId, Consumer<RunEvent> listener);

    Subscription subscribeAfter(String executionId, long afterSequence, Consumer<RunEvent> listener);

    @FunctionalInterface
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
