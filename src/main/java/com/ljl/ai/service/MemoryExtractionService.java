package com.ljl.ai.service;

import com.ljl.ai.config.MemoryConfig;
import com.ljl.ai.model.entity.UserLongTermMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Async;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 处理已持久化的用户原文。首版采用保守的确定性候选规则；后续可替换为受 schema 约束的提取模型。
 */
@Slf4j
@Service
public class MemoryExtractionService {
    private final MongoTemplate mongoTemplate;
    private final LongTermMemoryService longTermMemoryService;
    private final MemoryConfig config;
    private final MemoryCandidateExtractor candidateExtractor;

    public MemoryExtractionService(MongoTemplate mongoTemplate, LongTermMemoryService longTermMemoryService,
                                   MemoryConfig config) {
        this(mongoTemplate, longTermMemoryService, config,
                new DeterministicMemoryCandidateExtractor(longTermMemoryService));
    }

    @Autowired
    public MemoryExtractionService(MongoTemplate mongoTemplate, LongTermMemoryService longTermMemoryService,
                                   MemoryConfig config, MemoryCandidateExtractor candidateExtractor) {
        this.mongoTemplate = mongoTemplate;
        this.longTermMemoryService = longTermMemoryService;
        this.config = config;
        this.candidateExtractor = candidateExtractor;
    }

    /** 由会话持久化路径提交；不在用户请求线程中提取或向量化。 */
    @Async("memoryTaskExecutor")
    public void submit(String userId, String sessionId, String messageId, String content) {
        process(userId, sessionId, messageId, content);
    }

    /** 公开同步入口，供重放任务和无 Spring 代理的单元测试使用。 */
    public Outcome process(String userId, String sessionId, String messageId, String content) {
        return process(userId, sessionId, messageId, content, LocalDateTime.now());
    }

    /** sourceOccurredAt 必须来自落盘用户消息，不能用 worker 的处理时间代替。 */
    public Outcome process(String userId, String sessionId, String messageId, String content,
                           LocalDateTime sourceOccurredAt) {
        if (!config.getLongTerm().isGenerateEnabled()) {
            return Outcome.NO_OUTPUT;
        }
        Optional<MemoryCandidateExtractor.Proposal> extracted = candidateExtractor.extract(content);
        if (extracted.isEmpty()) {
            return Outcome.NO_OUTPUT;
        }
        MemoryCandidateExtractor.Proposal preference = extracted.get();
        if (!isValidProposal(preference, content)) {
            return Outcome.NO_OUTPUT;
        }
        LocalDateTime now = LocalDateTime.now();
        String idempotencyKey = userId + ":" + sessionId + ":" + messageId + ":v1";
        UserMemoryCandidate previous = mongoTemplate.findOne(new Query(Criteria.where("idempotencyKey").is(idempotencyKey)),
                UserMemoryCandidate.class);
        if (previous != null && previous.getStatus() == UserMemoryCandidate.Status.CONSOLIDATED) {
            return Outcome.CONSOLIDATED;
        }
        UserMemoryCandidate candidate = previous == null ? UserMemoryCandidate.builder()
                .candidateId(UUID.randomUUID().toString())
                .idempotencyKey(idempotencyKey)
                .userId(userId).sessionId(sessionId).messageId(messageId)
                .sourceOccurredAt(sourceOccurredAt)
                .evidence(content).content(preference.content()).type(preference.type())
                .scope(UserLongTermMemory.Scope.USER).canonicalKey(preference.canonicalKey())
                .status(UserMemoryCandidate.Status.PENDING).createTime(now).updateTime(now).build() : previous;
        candidate.setStatus(UserMemoryCandidate.Status.PENDING);
        candidate.setRejectionReason(null);
        try {
            mongoTemplate.save(candidate);
            longTermMemoryService.consolidateExplicitPreference(userId, sessionId, messageId,
                    preference.content(), preference.type(), preference.canonicalKey(), sourceOccurredAt);
            candidate.setStatus(UserMemoryCandidate.Status.CONSOLIDATED);
            return Outcome.CONSOLIDATED;
        } catch (RuntimeException exception) {
            candidate.setStatus(UserMemoryCandidate.Status.FAILED);
            candidate.setRejectionReason(exception.getClass().getSimpleName());
            log.warn("memory_extraction_failed userId={}, messageId={}, errorType={}", userId, messageId,
                    exception.getClass().getSimpleName());
            return Outcome.FAILED;
        } finally {
            candidate.setUpdateTime(LocalDateTime.now());
            mongoTemplate.save(candidate);
        }
    }

    public enum Outcome {
        CONSOLIDATED, NO_OUTPUT, FAILED
    }

    private boolean isValidProposal(MemoryCandidateExtractor.Proposal proposal, String source) {
        return proposal != null && proposal.content() != null && !proposal.content().isBlank()
                && proposal.evidence() != null && !proposal.evidence().isBlank()
                && source != null && source.contains(proposal.evidence())
                && proposal.type() != null && proposal.canonicalKey() != null && !proposal.canonicalKey().isBlank();
    }
}
