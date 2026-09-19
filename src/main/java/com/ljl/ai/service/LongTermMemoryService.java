package com.ljl.ai.service;

import com.ljl.ai.config.MemoryConfig;
import com.ljl.ai.model.entity.UserLongTermMemory;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LongTermMemoryService {
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final MongoTemplate mongoTemplate;
    private final MemoryConfig config;

    /** 保留旧接口；历史人工添加的自由文本以 LEGACY 类型读取，不会被自动提升为核心偏好。 */
    public UserLongTermMemory add(String userId, String content, List<String> tags) {
        LocalDateTime now = LocalDateTime.now();
        UserLongTermMemory memory = UserLongTermMemory.builder()
                .memoryId(UUID.randomUUID().toString())
                .userId(userId)
                .content(content)
                .tags(tags == null ? List.of() : List.copyOf(tags))
                .createTime(now)
                .updateTime(now)
                .recordedAt(now)
                .enabled(true)
                .memoryType(UserLongTermMemory.Type.LEGACY)
                .memoryScope(UserLongTermMemory.Scope.USER)
                .origin(UserLongTermMemory.Origin.LEGACY)
                .status(UserLongTermMemory.Status.ACTIVE)
                .version(1L)
                .sourceRefs(List.of())
                .build();
        return saveWithVector(memory);
    }

    /**
     * 发布一条有来源的用户明确偏好。相同维度的旧当前值先失效，迟到提取不能覆盖新值：
     * 调用方应仅传入已验证来自用户原文的候选。
     */
    public UserLongTermMemory consolidateExplicitPreference(String userId, String sessionId, String messageId,
                                                            String content, UserLongTermMemory.Type type,
                                                            String canonicalKey) {
        return consolidateExplicitPreference(userId, sessionId, messageId, content, type, canonicalKey,
                LocalDateTime.now());
    }

    /**
     * 同一维度的较早原文只可作为审计来源，不能在任务乱序时替代较新的当前值。
     */
    public UserLongTermMemory consolidateExplicitPreference(String userId, String sessionId, String messageId,
                                                            String content, UserLongTermMemory.Type type,
                                                            String canonicalKey, LocalDateTime sourceOccurredAt) {
        return consolidateExplicitPreference(userId, sessionId, messageId, content, type, canonicalKey,
                sourceOccurredAt, true);
    }

    /** 唯一索引发生竞争时重新读取一次当前值，随后走 MERGE / SUPERSEDE / IGNORE 规则。 */
    private UserLongTermMemory consolidateExplicitPreference(String userId, String sessionId, String messageId,
                                                             String content, UserLongTermMemory.Type type,
                                                             String canonicalKey, LocalDateTime sourceOccurredAt,
                                                             boolean retryAllowed) {
        LocalDateTime now = LocalDateTime.now();
        Query active = new Query(Criteria.where("userId").is(userId)
                .and("memoryScope").is(UserLongTermMemory.Scope.USER)
                .and("canonicalKey").is(canonicalKey)
                .and("status").is(UserLongTermMemory.Status.ACTIVE));
        UserLongTermMemory existing = mongoTemplate.findOne(active, UserLongTermMemory.class);
        String normalized = normalize(content);
        UserLongTermMemory.SourceRef source = UserLongTermMemory.SourceRef.builder()
                .sessionId(sessionId).messageId(messageId).evidence(content)
                .occurredAt(sourceOccurredAt == null ? now : sourceOccurredAt).build();
        if (!claimPreferenceSlot(userId, canonicalKey, source.getOccurredAt(), now)) {
            UserLongTermMemory current = mongoTemplate.findOne(active, UserLongTermMemory.class);
            if (current != null) return current;
        }
        if (existing != null && normalized.equals(normalize(existing.getCanonicalValue()))) {
            existing.setSourceRefs(appendSource(existing.getSourceRefs(), source));
            existing.setUpdateTime(now);
            existing.setVersion(nextVersion(existing));
            return mongoTemplate.save(existing);
        }
        if (existing != null && isOlderThanCurrent(source, existing)) {
            return existing;
        }
        if (existing != null) {
            existing.setStatus(UserLongTermMemory.Status.SUPERSEDED);
            existing.setEnabled(false);
            existing.setUpdateTime(now);
            existing.setVersion(nextVersion(existing));
            mongoTemplate.save(existing);
            removeVectorBestEffort(existing);
        }
        UserLongTermMemory memory = UserLongTermMemory.builder()
                .memoryId(UUID.randomUUID().toString())
                .userId(userId)
                .content(content)
                .tags(List.of(type.name().toLowerCase()))
                .memoryType(type)
                .memoryScope(UserLongTermMemory.Scope.USER)
                .canonicalKey(canonicalKey)
                .canonicalValue(content)
                .origin(UserLongTermMemory.Origin.AUTO_EXTRACTED)
                .status(UserLongTermMemory.Status.ACTIVE)
                .sourceRefs(List.of(source))
                .createTime(now).updateTime(now).recordedAt(now).validFrom(now)
                .enabled(true).version(1L)
                .supersedes(existing == null ? null : existing.getMemoryId())
                .build();
        try {
            return saveWithVector(memory);
        } catch (DuplicateKeyException conflict) {
            if (!retryAllowed) {
                throw conflict;
            }
            return consolidateExplicitPreference(userId, sessionId, messageId, content, type, canonicalKey,
                    sourceOccurredAt, false);
        }
    }

    /** 对纯用户原文作保守提取；临时要求、提问和助手推测均不能进入长期记忆。 */
    public Optional<ExtractedPreference> extractExplicitPreference(String content) {
        if (content == null || content.isBlank() || content.contains("？") || content.contains("?")) {
            return Optional.empty();
        }
        String text = content.trim();
        boolean durableMarker = text.matches("(?s).*(以后|今后|从现在起|请记住|后续都|之后都).*" );
        boolean temporaryMarker = text.matches("(?s).*(这次|本次|今天|当前|本轮).*" );
        if (!durableMarker || temporaryMarker) {
            return Optional.empty();
        }
        if (text.contains("风险")) {
            return Optional.of(new ExtractedPreference(text, UserLongTermMemory.Type.RESPONSE_PREFERENCE,
                    "response.risk_first"));
        }
        if (text.contains("简短") || text.contains("简洁")) {
            return Optional.of(new ExtractedPreference(text, UserLongTermMemory.Type.RESPONSE_PREFERENCE,
                    "response.brevity"));
        }
        if (text.contains("短线") || text.contains("中长期") || text.contains("长期")) {
            return Optional.of(new ExtractedPreference(text, UserLongTermMemory.Type.ANALYSIS_PREFERENCE,
                    "analysis.horizon"));
        }
        return Optional.empty();
    }

    public record ExtractedPreference(String content, UserLongTermMemory.Type type, String canonicalKey) {
    }

    /** 先写入带用户归属及版本的向量，再保存业务记忆；MongoDB 失败时补偿本次向量。 */
    private UserLongTermMemory saveWithVector(UserLongTermMemory memory) {
        TextSegment segment = TextSegment.from(memory.getContent(), Metadata.from(Map.of(
                "memoryId", memory.getMemoryId(),
                "memoryType", "USER_LONG_TERM",
                "userId", memory.getUserId(),
                "memoryVersion", String.valueOf(memory.getVersion()))));
        Embedding embedding = embeddingModel.embed(segment).content();
        String vectorId = embeddingStore.add(embedding, segment);
        memory.setVectorId(vectorId);
        try {
            return mongoTemplate.save(memory);
        } catch (Exception e) {
            // Mongo 写入失败时向量尚无可读业务记录；若本次回滚也失败，留下可恢复删除任务。
            removeVectorBestEffort(memory);
            throw e;
        }
    }

    /**
     * 先从 MongoDB 限定用户、状态与有效期，再对该用户的有限候选做语义排序。
     * 这避免共享向量库的全局 Top-K 被其他用户挤占；向量库仍承担写入和可重建索引职责。
     */
    public List<UserLongTermMemory> recall(String userId, String query) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        return list(userId).stream()
                .limit(Math.max(config.getLongTerm().getTopK(), config.getLongTerm().getMaxUserRecallCandidates()))
                .map(memory -> scored(memory, queryEmbedding))
                .filter(java.util.Objects::nonNull)
                .filter(score -> score.similarity() >= config.getLongTerm().getMinScore())
                .sorted(Comparator.comparingDouble(ScoredMemory::similarity).reversed()
                        .thenComparing(score -> score.memory().getUpdateTime(), Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(config.getLongTerm().getTopK())
                .map(ScoredMemory::memory)
                .toList();
    }

    public List<UserLongTermMemory> list(String userId) {
        return mongoTemplate.find(new Query(Criteria.where("userId").is(userId)), UserLongTermMemory.class)
                .stream().filter(memory -> isReadable(memory, userId)).toList();
    }

    /** 少量当前有效的 USER 范围偏好固定加载，避免依赖当前查询与偏好的语义相似度。 */
    public List<UserLongTermMemory> corePreferences(String userId) {
        if (!isReadEnabledFor(userId)) {
            return List.of();
        }
        return mongoTemplate.find(new Query(Criteria.where("userId").is(userId)
                        .and("memoryScope").is(UserLongTermMemory.Scope.USER)
                        .and("status").is(UserLongTermMemory.Status.ACTIVE)), UserLongTermMemory.class)
                .stream()
                .filter(memory -> isReadable(memory, userId))
                .filter(memory -> memory.getMemoryType() == UserLongTermMemory.Type.RESPONSE_PREFERENCE
                        || memory.getMemoryType() == UserLongTermMemory.Type.ANALYSIS_PREFERENCE
                        || memory.getMemoryType() == UserLongTermMemory.Type.EXPLICIT_CONSTRAINT)
                .sorted(Comparator.comparing(UserLongTermMemory::getUpdateTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(config.getLongTerm().getCorePreferenceLimit())
                .toList();
    }

    public boolean isReadEnabledFor(String userId) {
        if (!config.getLongTerm().isReadEnabled()) return false;
        List<String> allowlist = config.getLongTerm().getReadUserAllowlist();
        if (allowlist == null || allowlist.stream().noneMatch(value -> value != null && !value.isBlank())) return true;
        return allowlist.stream().anyMatch(value -> userId.equals(value == null ? null : value.trim()));
    }

    /** 软删除保留最小状态，阻止迟到后台任务或旧向量将内容重新注入上下文。 */
    public void delete(String userId, String memoryId) {
        UserLongTermMemory memory = mongoTemplate.findById(memoryId, UserLongTermMemory.class);
        if (memory == null || !userId.equals(memory.getUserId())) return;
        memory.setEnabled(false);
        memory.setStatus(UserLongTermMemory.Status.DELETED);
        memory.setUpdateTime(LocalDateTime.now());
        memory.setVersion(nextVersion(memory));
        mongoTemplate.save(memory);
        removeVectorBestEffort(memory);
    }

    /** 手工修正优先于自动提取；新记录通过 supersedes 保留可审计的版本链。 */
    public UserLongTermMemory update(String userId, String memoryId, String content, List<String> tags) {
        UserLongTermMemory current = mongoTemplate.findById(memoryId, UserLongTermMemory.class);
        if (current == null || !userId.equals(current.getUserId()) || !isReadable(current, userId)) {
            throw new IllegalArgumentException("长期记忆不存在或无权修改");
        }
        String canonicalKey = current.getCanonicalKey();
        if (canonicalKey == null || canonicalKey.isBlank()) {
            canonicalKey = "manual." + current.getMemoryId();
        }
        UserLongTermMemory.Type type = current.getMemoryType() == null
                || current.getMemoryType() == UserLongTermMemory.Type.LEGACY
                ? UserLongTermMemory.Type.EXPLICIT_CONSTRAINT : current.getMemoryType();
        UserLongTermMemory updated = consolidateExplicitPreference(userId, null, null, content, type, canonicalKey);
        updated.setOrigin(UserLongTermMemory.Origin.USER_EXPLICIT);
        updated.setTags(tags == null ? updated.getTags() : List.copyOf(tags));
        return mongoTemplate.save(updated);
    }

    /** 删除会话时撤销来源；没有其他来源支持的自动记忆立刻失效。 */
    public void revokeSessionSources(String userId, String sessionId) {
        List<UserLongTermMemory> memories = mongoTemplate.find(new Query(Criteria.where("userId").is(userId)
                .and("sourceRefs.sessionId").is(sessionId)), UserLongTermMemory.class);
        for (UserLongTermMemory memory : memories) {
            List<UserLongTermMemory.SourceRef> retained = memory.getSourceRefs() == null ? List.of()
                    : memory.getSourceRefs().stream().filter(ref -> !sessionId.equals(ref.getSessionId())).toList();
            memory.setSourceRefs(retained);
            memory.setUpdateTime(LocalDateTime.now());
            memory.setVersion(nextVersion(memory));
            if (retained.isEmpty() && memory.getOrigin() == UserLongTermMemory.Origin.AUTO_EXTRACTED) {
                memory.setEnabled(false);
                memory.setStatus(UserLongTermMemory.Status.DELETED);
                removeVectorBestEffort(memory);
            }
            mongoTemplate.save(memory);
        }
    }

    private boolean isReadable(UserLongTermMemory memory, String userId) {
        if (memory == null || !userId.equals(memory.getUserId()) || !Boolean.TRUE.equals(memory.getEnabled())) {
            return false;
        }
        if (memory.getStatus() != null && memory.getStatus() != UserLongTermMemory.Status.ACTIVE) {
            return false;
        }
        return memory.getValidTo() == null || memory.getValidTo().isAfter(LocalDateTime.now());
    }

    private List<UserLongTermMemory.SourceRef> appendSource(List<UserLongTermMemory.SourceRef> sources,
                                                             UserLongTermMemory.SourceRef candidate) {
        List<UserLongTermMemory.SourceRef> result = new ArrayList<>(sources == null ? List.of() : sources);
        boolean exists = result.stream().anyMatch(source -> java.util.Objects.equals(source.getMessageId(), candidate.getMessageId()));
        if (!exists) result.add(candidate);
        return List.copyOf(result);
    }

    private long nextVersion(UserLongTermMemory memory) {
        return memory.getVersion() == null ? 1L : memory.getVersion() + 1;
    }

    /** 条件更新是跨实例的顺序闸门：只允许不早于当前槽位来源时间的候选继续发布。 */
    private boolean claimPreferenceSlot(String userId, String canonicalKey, LocalDateTime sourceOccurredAt,
                                        LocalDateTime now) {
        String slotId = userId + ":USER:" + canonicalKey;
        Criteria acceptable = new Criteria().orOperator(Criteria.where("latestSourceOccurredAt").exists(false),
                Criteria.where("latestSourceOccurredAt").lte(sourceOccurredAt));
        Query query = new Query(new Criteria().andOperator(Criteria.where("_id").is(slotId), acceptable));
        Update update = new Update().setOnInsert("_id", slotId).set("latestSourceOccurredAt", sourceOccurredAt)
                .set("updateTime", now).inc("version", 1);
        try {
            MemoryPreferenceSlot slot = mongoTemplate.findAndModify(query, update,
                    FindAndModifyOptions.options().upsert(true).returnNew(true), MemoryPreferenceSlot.class);
            return slot == null || !slot.getLatestSourceOccurredAt().isAfter(sourceOccurredAt);
        } catch (DuplicateKeyException occupiedByNewerSource) {
            return false;
        }
    }

    private boolean isOlderThanCurrent(UserLongTermMemory.SourceRef candidate, UserLongTermMemory current) {
        if (candidate.getOccurredAt() == null || current.getSourceRefs() == null) {
            return false;
        }
        return current.getSourceRefs().stream()
                .map(UserLongTermMemory.SourceRef::getOccurredAt)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .map(latest -> candidate.getOccurredAt().isBefore(latest))
                .orElse(false);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim().toLowerCase(java.util.Locale.ROOT);
    }

    private ScoredMemory scored(UserLongTermMemory memory, Embedding queryEmbedding) {
        if (memory.getContent() == null || memory.getContent().isBlank()) {
            return null;
        }
        Embedding contentEmbedding = embeddingModel.embed(TextSegment.from(memory.getContent())).content();
        if (queryEmbedding == null || contentEmbedding == null) {
            return null;
        }
        return new ScoredMemory(memory, cosine(queryEmbedding.vector(), contentEmbedding.vector()));
    }

    private double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return -1D;
        }
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        return leftNorm == 0D || rightNorm == 0D ? -1D : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private record ScoredMemory(UserLongTermMemory memory, double similarity) {
    }

    private void removeVectorBestEffort(UserLongTermMemory memory) {
        if (memory.getVectorId() == null) {
            return;
        }
        try {
            embeddingStore.remove(memory.getVectorId());
        } catch (RuntimeException exception) {
            log.warn("长期记忆向量清理失败, memoryId={}, errorType={}", memory.getMemoryId(),
                    exception.getClass().getSimpleName());
            enqueueVectorCleanup(memory);
        }
    }

    /** 已提交删除标记的记忆可在后续轮次清理残留向量，不会重新暴露给读取路径。 */
    @Scheduled(fixedDelayString = "${memory.long-term.vector-cleanup-poll-delay-ms:60000}")
    void reconcileVectorDeletes() {
        LocalDateTime now = LocalDateTime.now();
        Query query = new Query(Criteria.where("status").is(MemoryVectorCleanupTask.Status.PENDING)
                .and("nextRunAt").lte(now)).with(Sort.by(Sort.Direction.ASC, "nextRunAt"))
                .limit(Math.max(1, config.getLongTerm().getJobBatchSize()));
        for (MemoryVectorCleanupTask task : mongoTemplate.find(query, MemoryVectorCleanupTask.class)) {
            try {
                embeddingStore.remove(task.getVectorId());
                task.setStatus(MemoryVectorCleanupTask.Status.COMPLETED);
            } catch (RuntimeException exception) {
                task.setAttempts(task.getAttempts() + 1);
                task.setNextRunAt(now.plusSeconds(Math.min(300, 1L << Math.min(8, task.getAttempts()))));
                log.warn("memory_vector_cleanup_failed taskId={}, attempts={}, errorType={}", task.getTaskId(),
                        task.getAttempts(), exception.getClass().getSimpleName());
            }
            task.setUpdateTime(now);
            mongoTemplate.save(task);
        }
    }

    /** MongoDB 中当前有效但缺少向量标识的记录会被补建；失效记录绝不在此路径复活。 */
    @Scheduled(fixedDelayString = "${memory.long-term.vector-rebuild-poll-delay-ms:300000}")
    void rebuildMissingVectors() {
        Query query = new Query(Criteria.where("status").is(UserLongTermMemory.Status.ACTIVE)
                .and("enabled").is(true).and("vectorId").is(null))
                .limit(Math.max(1, config.getLongTerm().getJobBatchSize()));
        for (UserLongTermMemory memory : mongoTemplate.find(query, UserLongTermMemory.class)) {
            if (memory.getContent() == null || memory.getContent().isBlank()) continue;
            try {
                TextSegment segment = TextSegment.from(memory.getContent(), Metadata.from(Map.of(
                        "memoryId", memory.getMemoryId(), "memoryType", "USER_LONG_TERM",
                        "userId", memory.getUserId(), "memoryVersion", String.valueOf(memory.getVersion()))));
                memory.setVectorId(embeddingStore.add(embeddingModel.embed(segment).content(), segment));
                memory.setUpdateTime(LocalDateTime.now());
                mongoTemplate.save(memory);
            } catch (RuntimeException exception) {
                log.warn("memory_vector_rebuild_failed memoryId={}, errorType={}", memory.getMemoryId(),
                        exception.getClass().getSimpleName());
            }
        }
    }

    private void enqueueVectorCleanup(UserLongTermMemory memory) {
        if (memory.getVectorId() == null || memory.getMemoryId() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        long version = memory.getVersion() == null ? 0L : memory.getVersion();
        String key = memory.getMemoryId() + ":" + version + ":DELETE";
        try {
            if (mongoTemplate.findOne(new Query(Criteria.where("idempotencyKey").is(key)),
                    MemoryVectorCleanupTask.class) == null) {
                mongoTemplate.save(MemoryVectorCleanupTask.builder().taskId(UUID.randomUUID().toString())
                        .idempotencyKey(key).memoryId(memory.getMemoryId()).vectorId(memory.getVectorId())
                        .memoryVersion(version).status(MemoryVectorCleanupTask.Status.PENDING).attempts(0)
                        .nextRunAt(now).createTime(now).updateTime(now).build());
            }
        } catch (RuntimeException persistFailure) {
            log.error("memory_vector_cleanup_enqueue_failed memoryId={}, errorType={}", memory.getMemoryId(),
                    persistFailure.getClass().getSimpleName());
        }
    }
}
