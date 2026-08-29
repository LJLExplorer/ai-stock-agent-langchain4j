package com.ljl.ai.workflow;

import java.util.Optional;

public interface ExecutionStateStore {

    Optional<ExecutionState> load(String executionId);

    ExecutionState save(ExecutionState state, long expectedVersion);
}
