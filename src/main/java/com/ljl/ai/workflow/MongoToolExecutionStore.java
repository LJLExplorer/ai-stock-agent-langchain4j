package com.ljl.ai.workflow;

import com.ljl.ai.research.FinancialFact;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Mongo 条件更新实现，禁止任意状态覆盖。 */
@Repository
public class MongoToolExecutionStore implements ToolExecutionStore {

    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    public MongoToolExecutionStore(MongoTemplate mongoTemplate) {
        this(mongoTemplate, Clock.systemUTC());
    }

    MongoToolExecutionStore(MongoTemplate mongoTemplate, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    @Override
    public ToolExecutionRecord begin(String executionId, String taskId, int attempt) {
        String id = ToolExecutionRecord.idOf(executionId, taskId, attempt);
        Instant now = Instant.now(clock);
        Query query = Query.query(Criteria.where("_id").is(id));
        Update update = new Update()
                .setOnInsert("_id", id)
                .setOnInsert("executionId", executionId)
                .setOnInsert("taskId", taskId)
                .setOnInsert("attempt", attempt)
                .setOnInsert("status", ToolExecutionRecord.Status.STARTED)
                .setOnInsert("evidence", List.of())
                .setOnInsert("startedAt", now);
        ToolExecutionRecord record = mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().upsert(true).returnNew(true), ToolExecutionRecord.class);
        if (record != null) {
            return record;
        }
        return find(executionId, taskId, attempt)
                .orElseThrow(() -> new IllegalStateException("TOOL_EXECUTION_BEGIN_FAILED"));
    }

    @Override
    public Optional<ToolExecutionRecord> find(String executionId, String taskId, int attempt) {
        return Optional.ofNullable(mongoTemplate.findById(
                ToolExecutionRecord.idOf(executionId, taskId, attempt), ToolExecutionRecord.class));
    }

    @Override
    public ToolExecutionRecord complete(String executionId, String taskId, int attempt,
                                        String resultSnapshot, List<FinancialFact> evidence) {
        List<FinancialFact> safeEvidence = evidence == null ? List.of() : List.copyOf(evidence);
        ToolExecutionRecord updated = transition(executionId, taskId, attempt,
                new Update().set("status", ToolExecutionRecord.Status.SUCCEEDED)
                        .set("resultSnapshot", resultSnapshot)
                        .set("evidence", safeEvidence)
                        .set("completedAt", Instant.now(clock)));
        if (updated != null) {
            return updated;
        }
        ToolExecutionRecord existing = find(executionId, taskId, attempt).orElse(null);
        if (existing != null && existing.status() == ToolExecutionRecord.Status.SUCCEEDED) {
            if (Objects.equals(existing.resultSnapshot(), resultSnapshot)
                    && Objects.equals(existing.evidence(), safeEvidence)) {
                return existing;
            }
            throw new IllegalStateException("TOOL_EXECUTION_CONFLICT");
        }
        throw new IllegalStateException("TOOL_EXECUTION_STATE_CONFLICT");
    }

    @Override
    public ToolExecutionRecord fail(String executionId, String taskId, int attempt, String errorMessage) {
        ToolExecutionRecord updated = transition(executionId, taskId, attempt,
                new Update().set("status", ToolExecutionRecord.Status.FAILED)
                        .set("errorMessage", errorMessage)
                        .set("completedAt", Instant.now(clock)));
        if (updated != null) {
            return updated;
        }
        ToolExecutionRecord existing = find(executionId, taskId, attempt).orElse(null);
        if (existing != null && existing.status() == ToolExecutionRecord.Status.FAILED
                && Objects.equals(existing.errorMessage(), errorMessage)) {
            return existing;
        }
        if (existing != null && existing.status() == ToolExecutionRecord.Status.SUCCEEDED) {
            throw new IllegalStateException("TOOL_EXECUTION_CONFLICT");
        }
        throw new IllegalStateException("TOOL_EXECUTION_STATE_CONFLICT");
    }

    private ToolExecutionRecord transition(String executionId, String taskId, int attempt, Update update) {
        String id = ToolExecutionRecord.idOf(executionId, taskId, attempt);
        Query query = Query.query(Criteria.where("_id").is(id)
                .and("status").is(ToolExecutionRecord.Status.STARTED));
        return mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), ToolExecutionRecord.class);
    }
}
