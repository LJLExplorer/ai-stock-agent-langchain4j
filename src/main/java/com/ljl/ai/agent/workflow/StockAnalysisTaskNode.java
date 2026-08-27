package com.ljl.ai.agent.workflow;

import com.alibaba.fastjson2.JSON;
import com.ljl.ai.agent.model.dto.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 执行单个任务并把结果写回工作流状态。
 */
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
        try {
            ToolResult<?> result = executor.execute(task.getTaskType(), symbol,
                    state.getOriginalQuestion(), "latest");
            if (result.isSuccess()) {
                task.complete(JSON.toJSONString(result.getData()));
            } else {
                task.fail(result.getErrorMessage());
            }
        } catch (Exception exception) {
            task.fail(exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }
}
