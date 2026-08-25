package com.ljl.ai.agent.workflow;

import org.springframework.stereotype.Component;

@Component
public class WorkflowRetryPolicy {

    private final int maxAttempts;

    public WorkflowRetryPolicy() {
        this(2);
    }

    public WorkflowRetryPolicy(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("最大重试次数必须大于0");
        }
        this.maxAttempts = maxAttempts;
    }

    public boolean canRetry(ExecutionTask task) {
        return task != null && task.getAttempts() < maxAttempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
