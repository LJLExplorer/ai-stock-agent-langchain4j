package com.ljl.ai.workflow;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class WorkflowRunner {

    private final StockAnalysisWorkflow workflow;
    private final ExecutionStateStore stateStore;

    public WorkflowRunner(StockAnalysisWorkflow workflow, ExecutionStateStore stateStore) {
        this.workflow = workflow;
        this.stateStore = stateStore;
    }

    public ExecutionState run(ExecutionState state) {
        log.info("workflow_execution_started executionId={}, traceId={}, status={}", state.getExecutionId(),
                state.getTraceId(), state.getWorkflowStatus());
        stateStore.save(state, -1);
        return execute(state);
    }

    public ExecutionState resume(String executionId) {
        ExecutionState state = stateStore.load(executionId)
                .orElseThrow(() -> new IllegalArgumentException("执行状态不存在: " + executionId));
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
            long previousVersion = state.getVersion();
            workflow.run(state);
            stateStore.save(state, previousVersion);
            log.info("workflow_execution_finished executionId={}, status={}, elapsedMs={}", state.getExecutionId(),
                    state.getWorkflowStatus(), elapsedMillis(started));
            return state;
        } catch (RuntimeException exception) {
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

    private long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }
}
