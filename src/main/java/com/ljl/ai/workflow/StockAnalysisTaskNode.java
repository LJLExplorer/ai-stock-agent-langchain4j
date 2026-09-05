package com.ljl.ai.workflow;

import com.alibaba.fastjson2.JSON;
import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.research.EvidencePackBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 执行单个任务并把结果写回工作流状态。
 */
@Slf4j
@Component
public class StockAnalysisTaskNode {

    private final StockAnalysisTaskExecutor executor;
    private final EvidencePackBuilder evidencePackBuilder;

    public StockAnalysisTaskNode(StockAnalysisTaskExecutor executor) {
        this(executor, new EvidencePackBuilder());
    }

    @Autowired
    public StockAnalysisTaskNode(StockAnalysisTaskExecutor executor, EvidencePackBuilder evidencePackBuilder) {
        this.executor = executor;
        this.evidencePackBuilder = evidencePackBuilder;
    }

    public void execute(ExecutionState state, ExecutionTask task) {
        if (task.getStatus() == TaskStatus.COMPLETED) {
            return;
        }
        String symbol = state.getPlan() == null ? null : state.getPlan().getSymbol();
        task.start();
        long started = System.nanoTime();
        log.info("tool_execution_started executionId={}, taskId={}, tool={}, symbol={}, queryLength={}, period={}, attempt={}",
                state.getExecutionId(), task.getTaskId(), task.getTaskType().toolName(), symbol,
                state.getOriginalQuestion() == null ? 0 : state.getOriginalQuestion().length(), "latest", task.getAttempts());
        try {
            ToolResult<?> result = state.getAnalysisContext() == null
                    ? executor.execute(task.getTaskType(), symbol, state.getOriginalQuestion(), "latest")
                    : executor.executeWithContext(task.getTaskType(), state.getAnalysisContext(),
                    state.getOriginalQuestion(), "latest");
            log.info("tool_execution_finished executionId={}, taskId={}, tool={}, success={}, elapsedMs={}, errorCode={}",
                    state.getExecutionId(), task.getTaskId(), task.getTaskType().toolName(), result.isSuccess(),
                    elapsedMillis(started), result.getErrorCode());
            if (result.isSuccess()) {
                var evidence = evidencePackBuilder.map(task.getTaskType(), result.getData(), state.getAnalysisContext());
                task.complete(JSON.toJSONString(result.getData()), evidence);
            } else {
                task.fail(result.getErrorMessage());
            }
            refreshEvidencePack(state);
        } catch (Exception exception) {
            log.error("tool_execution_failed executionId={}, taskId={}, tool={}, elapsedMs={}, errorType={}",
                    state.getExecutionId(), task.getTaskId(), task.getTaskType().toolName(), elapsedMillis(started),
                    exception.getClass().getSimpleName());
            task.fail(exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage());
            refreshEvidencePack(state);
        }
    }

    private void refreshEvidencePack(ExecutionState state) {
        if (state.getAnalysisContext() != null) {
            state.setEvidencePack(evidencePackBuilder.build(state.getAnalysisContext(), state.getTasks()));
        }
    }

    private long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }
}
