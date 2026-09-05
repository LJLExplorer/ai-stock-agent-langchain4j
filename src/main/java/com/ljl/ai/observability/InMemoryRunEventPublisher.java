package com.ljl.ai.observability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 单进程事件缓冲区；为 SSE 提供有界回放和轻量订阅回调。 */
@Component
public class InMemoryRunEventPublisher implements RunEventPublisher {
    private static final int DEFAULT_CAPACITY = 200;

    private final int capacity;
    private final Clock clock;
    private final ConcurrentHashMap<String, ExecutionEvents> executions = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryRunEventPublisher() {
        this(DEFAULT_CAPACITY, Clock.systemUTC());
    }

    InMemoryRunEventPublisher(int capacity, Clock clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须大于 0");
        }
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Override
    public RunEvent publish(String executionId, String traceId, RunEvent.EventType eventType,
                            String node, String summary) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId 不能为空");
        }
        ExecutionEvents execution = executions.computeIfAbsent(executionId, ignored -> new ExecutionEvents());
        RunEvent event = new RunEvent(executionId, traceId, execution.sequence.incrementAndGet(),
                Instant.now(clock), eventType, node, summary);
        synchronized (execution.buffer) {
            execution.buffer.addLast(event);
            while (execution.buffer.size() > capacity) {
                execution.buffer.removeFirst();
            }
        }
        execution.listeners.forEach(listener -> notifySafely(listener, event));
        return event;
    }

    @Override
    public List<RunEvent> snapshot(String executionId) {
        ExecutionEvents execution = executions.get(executionId);
        if (execution == null) {
            return List.of();
        }
        synchronized (execution.buffer) {
            return List.copyOf(new ArrayList<>(execution.buffer));
        }
    }

    @Override
    public Subscription subscribe(String executionId, Consumer<RunEvent> listener) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId 不能为空");
        }
        Objects.requireNonNull(listener, "listener 不能为空");
        ExecutionEvents execution = executions.computeIfAbsent(executionId, ignored -> new ExecutionEvents());
        execution.listeners.add(listener);
        return () -> execution.listeners.remove(listener);
    }

    private void notifySafely(Consumer<RunEvent> listener, RunEvent event) {
        try {
            listener.accept(event);
        } catch (RuntimeException ignored) {
            // 事件消费者失败不应中断主工作流；SSE 层会取消失效订阅。
        }
    }

    private static final class ExecutionEvents {
        private final AtomicLong sequence = new AtomicLong();
        private final Deque<RunEvent> buffer = new ArrayDeque<>();
        private final CopyOnWriteArrayList<Consumer<RunEvent>> listeners = new CopyOnWriteArrayList<>();
    }
}
