package com.ljl.ai.agent.workflow;

import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class WorkflowRunner {

    private final StockAnalysisWorkflow workflow;
    private final ExecutionStateStore stateStore;
    private final WorkflowReflector reflector;

    public WorkflowRunner(StockAnalysisWorkflow workflow, ExecutionStateStore stateStore,
                          WorkflowReflector reflector) {
        this.workflow = workflow;
        this.stateStore = stateStore;
        this.reflector = reflector;
    }

    public AgentState run(String question) {
        return workflow.compile()
                .invoke(Map.of("question", question))
                .orElseThrow(() -> new IllegalStateException("工作流未返回最终状态"));
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
        for (int cycle = 0; cycle < 3; cycle++) {
            long previousVersion = state.getVersion();
            if (state.getWorkflowStatus() != WorkflowStatus.RUNNING) {
                state.start();
                stateStore.save(state, previousVersion);
            }
            workflow.run(state);
            WorkflowReflector.ReflectionDecision decision = reflector.reflect(state);
            long decisionVersion = state.getVersion();
            if (decision.trusted()) {
                state.complete();
                stateStore.save(state, decisionVersion);
                return state;
            }
            if (cycle == 2) {
                state.fail(decision.reason());
                stateStore.save(state, decisionVersion);
                return state;
            }
            decision.retryTaskIds().forEach(taskId -> state.getTasks().stream()
                    .filter(task -> taskId.equals(task.getTaskId()))
                    .findFirst()
                    .ifPresent(task -> task.retry(decision.reason())));
            decision.additionalTasks().forEach(taskType -> {
                boolean exists = state.getTasks().stream().anyMatch(task -> task.getTaskType() == taskType);
                if (!exists) {
                    state.getTasks().add(ExecutionTask.pending(
                            taskType.name().toLowerCase() + "-supplement", taskType));
                }
            });
            state.retry(decision.reason());
            stateStore.save(state, decisionVersion);
        }
        throw new IllegalStateException("工作流未完成");
    }
}
