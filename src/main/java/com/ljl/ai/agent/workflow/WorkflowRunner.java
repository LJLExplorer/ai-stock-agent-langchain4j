package com.ljl.ai.agent.workflow;

import org.springframework.stereotype.Service;

@Service
public class WorkflowRunner {

    private final StockAnalysisWorkflow workflow;
    private final ExecutionStateStore stateStore;

    public WorkflowRunner(StockAnalysisWorkflow workflow, ExecutionStateStore stateStore) {
        this.workflow = workflow;
        this.stateStore = stateStore;
    }

    public ExecutionState run(ExecutionState state) {
        stateStore.save(state, -1);
        return execute(state);
    }

    public ExecutionState resume(String executionId) {
        ExecutionState state = stateStore.load(executionId)
                .orElseThrow(() -> new IllegalArgumentException("执行状态不存在: " + executionId));
        return execute(state);
    }

    private ExecutionState execute(ExecutionState state) {
        long previousVersion = state.getVersion();
        workflow.run(state);
        try {
            stateStore.save(state, previousVersion);
        } catch (CheckpointConflictException conflict) {
            long latestVersion = stateStore.load(state.getExecutionId())
                    .map(ExecutionState::getVersion)
                    .orElseThrow(() -> conflict);
            stateStore.save(state, latestVersion);
        }
        return state;
    }
}
