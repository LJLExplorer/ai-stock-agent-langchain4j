package com.ljl.ai.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MongoExecutionStateStoreTest {

    @Test
    void shouldLoadAndAtomicallySaveStateWithExpectedVersion() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MongoExecutionStateStore store = new MongoExecutionStateStore(mongoTemplate);
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析", java.util.List.of());
        when(mongoTemplate.findById("exec-1", ExecutionState.class)).thenReturn(state);
        when(mongoTemplate.findAndReplace(any(), eq(state), any(FindAndReplaceOptions.class)))
                .thenReturn(state);

        assertEquals(Optional.of(state), store.load("exec-1"));
        assertEquals(state, store.save(state, 0));
    }

    @Test
    void shouldRejectStaleCheckpointWhenMongoUpdateMatchesNothing() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MongoExecutionStateStore store = new MongoExecutionStateStore(mongoTemplate);
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析", java.util.List.of());
        when(mongoTemplate.findAndReplace(any(), eq(state), any(FindAndReplaceOptions.class)))
                .thenReturn(null);

        assertThrows(CheckpointConflictException.class, () -> store.save(state, 0));
    }
}
