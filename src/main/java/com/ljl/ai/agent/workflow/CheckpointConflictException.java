package com.ljl.ai.agent.workflow;

public class CheckpointConflictException extends RuntimeException {
    public CheckpointConflictException(String executionId, long expectedVersion) {
        super("执行状态版本冲突: executionId=" + executionId + ", expectedVersion=" + expectedVersion);
    }
}
