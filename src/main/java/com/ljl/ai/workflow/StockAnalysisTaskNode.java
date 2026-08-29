package com.ljl.ai.workflow;

import com.alibaba.fastjson2.JSON;
import com.ljl.ai.model.dto.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 执行单个任务并把结果写回工作流状态。
 */
@Slf4j
@Component
public class StockAnalysisTaskNode {

    private final StockAnalysisTaskExecutor executor;

    public StockAnalysisTaskNode(StockAnalysisTaskExecutor executor) {
        this.executor = executor;
    }

    public void execute(ExecutionState state, ExecutionTask task) {
        if (task.getStatus() == TaskStatus.COMPLETED) {
            return;
        }
        String symbol = state.getPlan() == null ? null : state.getPlan().getSymbol();
        task.start();
        long started = System.nanoTime();
        log.info("tool_execution_started executionId={}, taskId={}, tool={}, symbol={}, query={}, period={}, attempt={}",
                state.getExecutionId(), task.getTaskId(), task.getTaskType().toolName(), symbol,
                state.getOriginalQuestion(), "latest", task.getAttempts());
        try {
            ToolResult<?> result = executor.execute(task.getTaskType(), symbol,
                    state.getOriginalQuestion(), "latest");
            log.info("tool_execution_finished executionId={}, taskId={}, tool={}, success={}, elapsedMs={}, result={}",
                    state.getExecutionId(), task.getTaskId(), task.getTaskType().toolName(), result.isSuccess(),
                    elapsedMillis(started), JSON.toJSONString(result));
            if (result.isSuccess()) {
                task.complete(JSON.toJSONString(result.getData()));
            } else {
                task.fail(result.getErrorMessage());
            }
        } catch (Exception exception) {
            log.error("tool_execution_failed executionId={}, taskId={}, tool={}, elapsedMs={}, error={}",
                    state.getExecutionId(), task.getTaskId(), task.getTaskType().toolName(), elapsedMillis(started),
                    exception.getMessage(), exception);
            task.fail(exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    private long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }
}
