package com.ljl.ai.workflow;

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

@Slf4j
@Service
public class WorkflowRunner {

    static final String GRAPH_VERSION = "stock-analysis-v1";

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

    public ExecutionState run(ExecutionState state) {
        log.info("workflow_execution_started executionId={}, traceId={}, status={}", state.getExecutionId(),
                state.getTraceId(), state.getWorkflowStatus());
        initializeMetadata(state);
        stateStore.save(state, -1);
        publish(state, RunEvent.EventType.PLAN_CREATED, "PLAN",
                "graphVersion=" + state.getGraphVersion());
        return execute(state);
    }

    public ExecutionState resume(String executionId) {
        ExecutionState state = stateStore.load(executionId)
                .orElseThrow(() -> new IllegalArgumentException("执行状态不存在: " + executionId));
        validateCompatibility(state);
        log.info("workflow_execution_resumed executionId={}, traceId={}, status={}", state.getExecutionId(),
                state.getTraceId(), state.getWorkflowStatus());
        return execute(state);
    }

    private ExecutionState execute(ExecutionState state) {
        String previousTraceId = MDC.get("traceId");
        if (state.getTraceId() != null) {
            MDC.put("traceId", state.getTraceId());
        }
        long started = System.nanoTime();
        try {
            workflow.run(state, stateStore::save);
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
            publish(state, RunEvent.EventType.WORKFLOW_FAILED, state.getCurrentNode(),
                    "errorCode=" + exception.getClass().getSimpleName());
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

    private void publish(ExecutionState state, RunEvent.EventType eventType, String node, String summary) {
        if (eventPublisher == null) {
            return;
        }
        RunEvent event = eventPublisher.publish(state.getExecutionId(), state.getTraceId(), eventType, node, summary);
        state.setEventSequence(event.sequence());
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

    private void validateCompatibility(ExecutionState state) {
        if (!GRAPH_VERSION.equals(state.getGraphVersion())) {
            throw incompatible("graphVersion", state.getGraphVersion(), GRAPH_VERSION);
        }
        String currentPlanHash = planHash(state.getPlan());
        if (!currentPlanHash.equals(state.getPlanHash())) {
            throw incompatible("planHash", state.getPlanHash(), currentPlanHash);
        }
    }

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
