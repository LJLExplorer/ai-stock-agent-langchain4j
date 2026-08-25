package com.ljl.ai.agent.workflow;

import java.util.Optional;

public interface ExecutionStateStore {

    Optional<ExecutionState> load(String executionId);

    ExecutionState save(ExecutionState state, long expectedVersion);
}
