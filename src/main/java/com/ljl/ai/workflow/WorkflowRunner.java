package com.ljl.ai.workflow;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import com.ljl.ai.observability.RunEvent;
import com.ljl.ai.observability.RunEventPublisher;
import com.ljl.ai.planner.AgentPlan;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
public class WorkflowRunner {

    static final String GRAPH_VERSION = "stock-analysis-v3";

    private final StockAnalysisWorkflow workflow;
    private final ExecutionStateStore stateStore;
    private final RunEventPublisher eventPublisher;

    public WorkflowRunner(StockAnalysisWorkflow workflow, ExecutionStateStore stateStore) {
        this(workflow, stateStore, null);
    }

    @Autowired
    public WorkflowRunner(StockAnalysisWorkflow workflow, ExecutionStateStore stateStore,
                          RunEventPublisher eventPublisher) {
        this.workflow = workflow;
        this.stateStore = stateStore;
        this.eventPublisher = eventPublisher;
    }

    /** 初始化图版本和计划摘要，先持久化初始状态，再发布计划事件并启动工作流。 */
    public ExecutionState run(ExecutionState state) {
        log.info("workflow_execution_started executionId={}, traceId={}, status={}", state.getExecutionId(),
                state.getTraceId(), state.getWorkflowStatus());
        initializeMetadata(state);
        stateStore.save(state, initialExpectedVersion(state));
        restoreEventSequence(state);
        publish(state, RunEvent.EventType.PLAN_CREATED, "PLAN",
                "graphVersion=" + state.getGraphVersion() + ";taskCount=" + state.getTasks().size());
        return execute(state);
    }

    /** 仅允许新建执行或接管匹配的异步接单占位记录，返回本次保存所需的 CAS 期望版本。 */
    private long initialExpectedVersion(ExecutionState state) {
        Optional<ExecutionState> existing = stateStore.load(state.getExecutionId());
        if (existing.isEmpty()) {
            return -1;
        }
        ExecutionState placeholder = existing.get();
        if (!isAcceptedPlaceholder(placeholder, state)) {
            throw new IllegalStateException("EXECUTION_STATE_ALREADY_EXISTS");
        }
        // 接管占位记录也是一次状态更新，必须推进版本，阻止并发接管使用相同 CAS 条件成功。
        state.setVersion(Math.addExact(placeholder.getVersion(), 1));
        state.setEventSequence(Math.max(state.getEventSequence(), placeholder.getEventSequence()));
        return placeholder.getVersion();
    }

    private boolean isAcceptedPlaceholder(ExecutionState existing, ExecutionState replacement) {
        return existing.getWorkflowStatus() == WorkflowStatus.PLANNED
                && existing.getPlan() == null
                && (existing.getTasks() == null || existing.getTasks().isEmpty())
                && existing.getGraphVersion() == null
                && existing.getPlanHash() == null
                && existing.getCurrentNode() == null
                && existing.getLastCompletedNode() == null
                && Objects.equals(existing.getExecutionId(), replacement.getExecutionId())
                && Objects.equals(existing.getUserId(), replacement.getUserId())
                && Objects.equals(existing.getSessionId(), replacement.getSessionId())
                && Objects.equals(existing.getOriginalQuestion(), replacement.getOriginalQuestion());
    }

    /**
     * 从持久化检查点显式恢复执行；已完成的执行直接返回，其余先校验图版本和计划摘要。
     * 失败执行必须先以 CAS 提交重试状态，防止版本冲突后仍触发工具或模型调用。
     */
    public ExecutionState resume(String executionId) {
        ExecutionState state = stateStore.load(executionId)
                .orElseThrow(() -> new IllegalArgumentException("执行状态不存在: " + executionId));
        if (state.getWorkflowStatus() == WorkflowStatus.COMPLETED) {
            return state;
        }
        validateCompatibility(state);
        boolean retryingFailure = state.getWorkflowStatus() == WorkflowStatus.FAILED;
        if (retryingFailure) {
            // 显式恢复先提交合法的重试状态；CAS 失败时不能继续调用工具或模型。
            long expectedVersion = state.getVersion();
            state.retry(null);
            if ("FAILED".equals(state.getLastCompletedNode())) {
                state.setNextNode("INIT");
                state.setReflectionDecision(null);
                state.setCriticDecision(null);
            }
            stateStore.save(state, expectedVersion);
        }
        restoreEventSequence(state);
        if (retryingFailure) {
            publish(state, RunEvent.EventType.WORKFLOW_RETRYING, "RESUME", "status=retrying;reason=manual_resume");
        }
        log.info("workflow_execution_resumed executionId={}, traceId={}, status={}", state.getExecutionId(),
                state.getTraceId(), state.getWorkflowStatus());
        return execute(state);
    }

    private void restoreEventSequence(ExecutionState state) {
        if (eventPublisher == null) return;
        try {
            eventPublisher.restoreSequence(state.getExecutionId(), state.getEventSequence());
        } catch (RuntimeException exception) {
            log.warn("workflow_event_sequence_restore_failed executionId={}, errorType={}",
                    state.getExecutionId(), exception.getClass().getSimpleName());
        }
    }

    /**
     * 驱动状态图，并在每次检查点成功保存后更新本地提交凭据。
     * 异常处理只基于最后已提交快照写入失败状态，避免覆盖其他执行者的新版本或撤销已完成结果。
     */
    private ExecutionState execute(ExecutionState state) {
        String previousTraceId = MDC.get("traceId");
        if (state.getTraceId() != null) {
            MDC.put("traceId", state.getTraceId());
        }
        long started = System.nanoTime();
        CheckpointProgress progress = new CheckpointProgress(state);
        try {
            ExecutionState result = workflow.run(state, (current, expectedVersion) -> {
                stateStore.save(current, expectedVersion);
                progress.committed = WorkflowAgentState.copyOf(current);
            });
            if (result == null) throw new IllegalStateException("WORKFLOW_RETURNED_NO_STATE");
            state = result;
            if (state.getWorkflowStatus() != WorkflowStatus.COMPLETED
                    && state.getWorkflowStatus() != WorkflowStatus.FAILED) {
                throw new IllegalStateException("WORKFLOW_DID_NOT_REACH_TERMINAL_STATE");
            }
            if (state.getFinalAnswer() != null && !state.getFinalAnswer().isBlank()) {
                publish(state, RunEvent.EventType.ANSWER_READY, "ANSWER", "answer=ready");
            }
            RunEvent.EventType terminal = state.getWorkflowStatus() == WorkflowStatus.FAILED
                    ? RunEvent.EventType.WORKFLOW_FAILED : RunEvent.EventType.WORKFLOW_COMPLETED;
            publish(state, terminal, state.getCurrentNode(), "status=" + state.getWorkflowStatus());
            log.info("workflow_execution_finished executionId={}, status={}, elapsedMs={}", state.getExecutionId(),
                    state.getWorkflowStatus(), elapsedMillis(started));
            return state;
        } catch (RuntimeException exception) {
            state = progress.committed;
            if (state.getWorkflowStatus() == WorkflowStatus.COMPLETED) {
                // 提交之后的通知失败不能撤销已完成的业务结果，SSE 可从检查点补偿。
                log.warn("workflow_post_completion_failed executionId={}, errorType={}",
                        state.getExecutionId(), exception.getClass().getSimpleName());
                return state;
            }
            // 使用当前执行持有的版本进行 CAS，不能覆盖其他执行者的新检查点。
            try {
                if (isCheckpointConflict(exception)) {
                    throw exception;
                }
                long expectedVersion = state.getVersion();
                state.fail(exception.getClass().getSimpleName());
                stateStore.save(state, expectedVersion);
                publish(state, RunEvent.EventType.WORKFLOW_FAILED, state.getCurrentNode(),
                        "errorCode=" + exception.getClass().getSimpleName());
            } catch (RuntimeException checkpointError) {
                if (checkpointError != exception) exception.addSuppressed(checkpointError);
            }
            log.error("workflow_execution_failed executionId={}, elapsedMs={}, errorType={}", state.getExecutionId(),
                    elapsedMillis(started), exception.getClass().getSimpleName());
            throw exception;
        } finally {
            if (previousTraceId == null) {
                MDC.remove("traceId");
            } else {
                MDC.put("traceId", previousTraceId);
            }
        }
    }

    /** 串行检查点提交凭据，仅用于失败处理，不参与节点计算或路由。 */
    private static final class CheckpointProgress {
        private ExecutionState committed;

        private CheckpointProgress(ExecutionState initial) {
            committed = WorkflowAgentState.copyOf(initial);
        }
    }

    private void publish(ExecutionState state, RunEvent.EventType eventType, String node, String summary) {
        if (eventPublisher == null) {
            return;
        }
        try {
            RunEvent event = eventPublisher.publish(state.getExecutionId(), state.getTraceId(), eventType, node, summary);
            state.setEventSequence(event.sequence());
        } catch (RuntimeException exception) {
            log.warn("workflow_event_publication_failed executionId={}, eventType={}, errorType={}",
                    state.getExecutionId(), eventType, exception.getClass().getSimpleName());
        }
    }

    private boolean isCheckpointConflict(Throwable exception) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = exception; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof CheckpointConflictException) return true;
        }
        return false;
    }

    private long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private void initializeMetadata(ExecutionState state) {
        String hash = planHash(state.getPlan());
        if (state.getGraphVersion() != null && !GRAPH_VERSION.equals(state.getGraphVersion())) {
            throw incompatible("graphVersion", state.getGraphVersion(), GRAPH_VERSION);
        }
        if (state.getPlanHash() != null && !hash.equals(state.getPlanHash())) {
            throw incompatible("planHash", state.getPlanHash(), hash);
        }
        state.setGraphVersion(GRAPH_VERSION);
        state.setPlanHash(hash);
    }

    /** 拒绝与当前图结构或计划不兼容的检查点，避免从旧快照恢复到错误的节点语义。 */
    private void validateCompatibility(ExecutionState state) {
        if (!GRAPH_VERSION.equals(state.getGraphVersion())) {
            throw incompatible("graphVersion", state.getGraphVersion(), GRAPH_VERSION);
        }
        String currentPlanHash = planHash(state.getPlan());
        if (!currentPlanHash.equals(state.getPlanHash())) {
            throw incompatible("planHash", state.getPlanHash(), currentPlanHash);
        }
    }

    /** 将意图、标的和排序后的任务类型规范化后计算摘要，使任务排列顺序不影响兼容性判断。 */
    static String planHash(AgentPlan plan) {
        String canonical;
        if (plan == null) {
            canonical = "";
        } else {
            String tasks = plan.getTasks() == null ? "" : plan.getTasks().stream()
                    .map(Enum::name).sorted(Comparator.naturalOrder()).reduce((a, b) -> a + "," + b).orElse("");
            canonical = value(plan.getIntent()) + "|" + value(plan.getSymbol()) + "|" + tasks;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private IllegalStateException incompatible(String field, Object actual, Object expected) {
        return new IllegalStateException("INCOMPATIBLE_CHECKPOINT: " + field
                + " 不匹配, actual=" + actual + ", expected=" + expected);
    }
}
