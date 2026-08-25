package com.ljl.ai.agent.workflow;

import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class MongoExecutionStateStore implements ExecutionStateStore {

    private final MongoTemplate mongoTemplate;

    public MongoExecutionStateStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<ExecutionState> load(String executionId) {
        return Optional.ofNullable(mongoTemplate.findById(executionId, ExecutionState.class));
    }

    @Override
    public ExecutionState save(ExecutionState state, long expectedVersion) {
        if (expectedVersion < 0) {
            return mongoTemplate.insert(state);
        }
        Query query = Query.query(Criteria.where("_id").is(state.getExecutionId())
                .and("version").is(expectedVersion));
        ExecutionState saved = mongoTemplate.findAndReplace(
                query, state, FindAndReplaceOptions.options().returnNew());
        if (saved == null) {
            throw new CheckpointConflictException(state.getExecutionId(), expectedVersion);
        }
        return saved;
    }
}
