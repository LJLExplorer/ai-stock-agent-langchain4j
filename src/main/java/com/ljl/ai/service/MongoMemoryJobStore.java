package com.ljl.ai.service;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/** Mongo 条件更新实现。重复入队不重置已完成任务，过期租约可以被其他 worker 接管。 */
@Repository
public class MongoMemoryJobStore implements MemoryJobStore {
    private final MongoTemplate mongoTemplate;

    public MongoMemoryJobStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public MemoryProcessingJob enqueue(String userId, String sessionId, String messageId, String content,
                                       LocalDateTime sourceOccurredAt, Instant now) {
        String key = userId + ":" + sessionId + ":" + messageId + ":v1";
        Query query = Query.query(Criteria.where("idempotencyKey").is(key));
        Update update = new Update()
                .setOnInsert("_id", UUID.randomUUID().toString())
                .setOnInsert("idempotencyKey", key)
                .setOnInsert("userId", userId)
                .setOnInsert("sessionId", sessionId)
                .setOnInsert("messageId", messageId)
                .setOnInsert("content", content)
                .setOnInsert("sourceOccurredAt", sourceOccurredAt)
                .setOnInsert("status", MemoryProcessingJob.Status.PENDING)
                .setOnInsert("attempts", 0)
                .setOnInsert("nextRunAt", now)
                .setOnInsert("createTime", now)
                .setOnInsert("updateTime", now);
        return mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().upsert(true).returnNew(true),
                MemoryProcessingJob.class);
    }

    @Override
    public Optional<MemoryProcessingJob> claim(String workerId, Instant now, int leaseSeconds) {
        Criteria runnable = new Criteria().orOperator(
                Criteria.where("status").in(MemoryProcessingJob.Status.PENDING, MemoryProcessingJob.Status.RETRY)
                        .and("nextRunAt").lte(now),
                Criteria.where("status").is(MemoryProcessingJob.Status.RUNNING).and("leaseUntil").lt(now));
        Query query = Query.query(runnable).with(Sort.by(Sort.Direction.ASC, "nextRunAt", "createTime"));
        Update update = new Update().set("status", MemoryProcessingJob.Status.RUNNING)
                .set("leaseOwner", workerId).set("leaseUntil", now.plusSeconds(leaseSeconds))
                .set("updateTime", now).inc("attempts", 1);
        return Optional.ofNullable(mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), MemoryProcessingJob.class));
    }

    @Override
    public void complete(String jobId, String workerId, MemoryProcessingJob.Status status, Instant now) {
        Query query = Query.query(Criteria.where("_id").is(jobId).and("status").is(MemoryProcessingJob.Status.RUNNING)
                .and("leaseOwner").is(workerId));
        Update update = new Update().set("status", status).set("updateTime", now)
                .unset("leaseOwner").unset("leaseUntil");
        mongoTemplate.updateFirst(query, update, MemoryProcessingJob.class);
    }

    @Override
    public void retry(String jobId, String workerId, Instant now, Instant nextRunAt, String error, boolean dead) {
        Query query = Query.query(Criteria.where("_id").is(jobId).and("status").is(MemoryProcessingJob.Status.RUNNING)
                .and("leaseOwner").is(workerId));
        Update update = new Update().set("status", dead ? MemoryProcessingJob.Status.DEAD : MemoryProcessingJob.Status.RETRY)
                .set("lastError", error).set("nextRunAt", nextRunAt).set("updateTime", now)
                .unset("leaseOwner").unset("leaseUntil");
        mongoTemplate.updateFirst(query, update, MemoryProcessingJob.class);
    }
}
