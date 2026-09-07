package com.ljl.ai.observability;

import java.time.Duration;
import java.util.Comparator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 单进程事件缓冲区；为 SSE 提供有界回放和轻量订阅回调。 */
@Component
public class InMemoryRunEventPublisher implements RunEventPublisher {
    private static final int DEFAULT_CAPACITY = 200;
    private static final int MAX_EXECUTIONS = 1024;
    private static final Duration RETENTION = Duration.ofHours(1);

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

    /** 在单次执行的锁内分配递增序号、维护有界回放缓存并排队通知订阅者，保证事件顺序一致。 */
    @Override
    public RunEvent publish(String executionId, String traceId, RunEvent.EventType eventType,
                            String node, String summary) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId 不能为空");
        }
        ExecutionEvents execution = executionFor(executionId);
        try {
            synchronized (execution.buffer) {
                RunEvent event = new RunEvent(executionId, traceId, Math.addExact(execution.sequence.get(), 1),
                        Instant.now(clock), eventType, node, summary, execution.streamId);
                execution.sequence.set(event.sequence());
                execution.updatedAt = event.occurredAt();
                execution.buffer.addLast(event);
                while (execution.buffer.size() > capacity) {
                    execution.buffer.removeFirst();
                }
                execution.terminal = eventType == RunEvent.EventType.WORKFLOW_COMPLETED
                        || eventType == RunEvent.EventType.WORKFLOW_FAILED;
                execution.pending.addLast(new Delivery(event, List.copyOf(execution.listeners)));
                drain(execution);
                return event;
            }
        } finally {
            release(executionId, execution);
        }
    }

    @Override
    public void restoreSequence(String executionId, long sequence) {
        if (executionId == null || executionId.isBlank() || sequence < 0) {
            throw new IllegalArgumentException("executionId 不能为空且 sequence 不能小于 0");
        }
        if (sequence == 0) return;
        ExecutionEvents execution = executionFor(executionId);
        try {
            synchronized (execution.buffer) {
                execution.sequence.set(Math.max(execution.sequence.get(), sequence));
            }
        } finally {
            release(executionId, execution);
        }
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
        ExecutionEvents execution = executionFor(executionId);
        try {
            synchronized (execution.buffer) {
                execution.listeners.add(listener);
            }
            return () -> unsubscribe(executionId, execution, listener);
        } finally {
            release(executionId, execution);
        }
    }

    /** 在同一锁内注册订阅并回放游标之后的缓存事件，消除快照读取与订阅建立之间的漏事件窗口。 */
    @Override
    public Subscription subscribeAfter(String executionId, long afterSequence, Consumer<RunEvent> listener) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId 不能为空");
        }
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence 不能小于 0");
        }
        Objects.requireNonNull(listener, "listener 不能为空");
        ExecutionEvents execution = executionFor(executionId);
        SequencedListener sequenced = new SequencedListener(afterSequence, listener);
        try {
            synchronized (execution.buffer) {
                boolean wasDispatching = execution.dispatching;
                execution.dispatching = true;
                execution.listeners.add(sequenced);
                try {
                    // 回放期间允许回调发布新事件，但新事件必须排在整个回放之后。
                    List.copyOf(execution.buffer).stream()
                            .filter(event -> event.sequence() > afterSequence)
                            .forEach(event -> notifySafely(sequenced, event));
                } finally {
                    execution.dispatching = wasDispatching;
                    if (!wasDispatching) drain(execution);
                }
            }
            return () -> unsubscribe(executionId, execution, sequenced);
        } finally {
            release(executionId, execution);
        }
    }

    /** 在单个执行的锁内排队分发，防止监听器重入导致其他监听器先收到后续事件。 */
    private void drain(ExecutionEvents execution) {
        if (execution.dispatching) return;
        execution.dispatching = true;
        try {
            while (!execution.pending.isEmpty()) {
                Delivery delivery = execution.pending.removeFirst();
                delivery.listeners().forEach(listener -> {
                    if (execution.listeners.contains(listener)) notifySafely(listener, delivery.event());
                });
            }
        } finally {
            execution.dispatching = false;
        }
    }

    private void notifySafely(Consumer<RunEvent> listener, RunEvent event) {
        try {
            listener.accept(event);
        } catch (RuntimeException ignored) {
            // 事件消费者失败不应中断主工作流；SSE 层会取消失效订阅。
        }
    }

    /** 获取并引用执行缓冲区；容量不足时只淘汰无使用者和订阅者的终态执行，避免清掉活跃事件流。 */
    private synchronized ExecutionEvents executionFor(String executionId) {
        ExecutionEvents existing = executions.get(executionId);
        if (existing != null) {
            existing.inUse++;
            return existing;
        }
        Instant cutoff = Instant.now(clock).minus(RETENTION);
        executions.entrySet().removeIf(entry -> entry.getValue().inUse == 0 && entry.getValue().terminal
                && entry.getValue().listeners.isEmpty()
                && entry.getValue().updatedAt.isBefore(cutoff));
        if (executions.size() >= MAX_EXECUTIONS) {
            executions.entrySet().stream()
                    .filter(entry -> entry.getValue().inUse == 0 && entry.getValue().terminal
                            && entry.getValue().listeners.isEmpty())
                    .min(Comparator.comparing(entry -> entry.getValue().updatedAt))
                    .ifPresent(entry -> executions.remove(entry.getKey(), entry.getValue()));
        }
        if (executions.size() >= MAX_EXECUTIONS) {
            throw new IllegalStateException("事件缓冲区已满，请稍后重试");
        }
        ExecutionEvents created = new ExecutionEvents();
        created.inUse = 1;
        executions.put(executionId, created);
        return created;
    }

    private synchronized void release(String executionId, ExecutionEvents execution) {
        execution.inUse--;
        removeEmpty(executionId, execution);
    }

    private synchronized void unsubscribe(String executionId, ExecutionEvents execution, Consumer<RunEvent> listener) {
        execution.listeners.remove(listener);
        removeEmpty(executionId, execution);
    }

    private void removeEmpty(String executionId, ExecutionEvents execution) {
        if (execution.inUse == 0 && execution.sequence.get() == 0 && execution.listeners.isEmpty()) {
            executions.remove(executionId, execution);
        }
    }

    private record Delivery(RunEvent event, List<Consumer<RunEvent>> listeners) { }

    private static final class ExecutionEvents {
        // 缓存重建（包括进程重启）后更换标识，前端不能跨事件流比较序号。
        private final String streamId = UUID.randomUUID().toString();
        // 仅在注册表锁内访问，保护获取对象到注册监听器之间的窗口不被淘汰。
        private int inUse;
        private boolean dispatching;
        private volatile Instant updatedAt = Instant.EPOCH;
        private volatile boolean terminal;
        private final AtomicLong sequence = new AtomicLong();
        private final Deque<RunEvent> buffer = new ArrayDeque<>();
        private final Deque<Delivery> pending = new ArrayDeque<>();
        private final CopyOnWriteArrayList<Consumer<RunEvent>> listeners = new CopyOnWriteArrayList<>();
    }

    private static final class SequencedListener implements Consumer<RunEvent> {
        private final AtomicLong lastSequence;
        private final Consumer<RunEvent> delegate;

        private SequencedListener(long lastSequence, Consumer<RunEvent> delegate) {
            this.lastSequence = new AtomicLong(lastSequence);
            this.delegate = delegate;
        }

        @Override
        public void accept(RunEvent event) {
            long previous;
            do {
                previous = lastSequence.get();
                if (event.sequence() <= previous) {
                    return;
                }
            } while (!lastSequence.compareAndSet(previous, event.sequence()));
            delegate.accept(event);
        }
    }
}
