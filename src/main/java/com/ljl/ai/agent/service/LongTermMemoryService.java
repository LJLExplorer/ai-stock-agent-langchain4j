package com.ljl.ai.agent.service;

import com.ljl.ai.agent.config.MemoryConfig;
import com.ljl.ai.agent.model.entity.UserLongTermMemory;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LongTermMemoryService {
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final MongoTemplate mongoTemplate;
    private final MemoryConfig config;

    public UserLongTermMemory add(String userId, String content, List<String> tags) {
        String memoryId = UUID.randomUUID().toString();
        TextSegment segment = TextSegment.from(content, Metadata.from(Map.of(
                "memoryId", memoryId,
                "memoryType", "USER_LONG_TERM",
                "userId", userId)));
        Embedding embedding = embeddingModel.embed(segment).content();
        String vectorId = embeddingStore.add(embedding, segment);
        UserLongTermMemory memory = UserLongTermMemory.builder()
                .memoryId(memoryId)
                .userId(userId)
                .content(content)
                .tags(tags == null ? List.of() : tags)
                .vectorId(vectorId)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .enabled(true)
                .build();
        try {
            return mongoTemplate.save(memory);
        } catch (Exception e) {
            try {
                embeddingStore.remove(vectorId);
            } catch (Exception cleanupError) {
                log.error("长期记忆回滚向量失败, vectorId: {}", vectorId, cleanupError);
            }
            throw e;
        }
    }

    public List<UserLongTermMemory> recall(String userId, String query) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        // 向量库是共享的，必须给用户过滤预留足够候选，不能直接使用全局 Top-K。
        int candidates = Math.max(config.getLongTerm().getTopK() * 100, 100);
        var result = embeddingStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(candidates)
                .minScore(config.getLongTerm().getMinScore())
                .build());
        List<UserLongTermMemory> memories = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            TextSegment segment = match.embedded();
            Metadata metadata = segment.metadata();
            if (metadata == null) {
                continue;
            }
            if (!userId.equals(metadata.getString("userId"))) {
                continue;
            }
            UserLongTermMemory memory = mongoTemplate.findById(
                    metadata.getString("memoryId"), UserLongTermMemory.class);
            if (memory != null && Boolean.TRUE.equals(memory.getEnabled())) {
                memories.add(memory);
            }
            if (memories.size() >= config.getLongTerm().getTopK()) {
                break;
            }
        }
        return memories;
    }

    public List<UserLongTermMemory> list(String userId) {
        return mongoTemplate.find(new Query(Criteria.where("userId").is(userId)
                .and("enabled").is(true)), UserLongTermMemory.class);
    }

    public void delete(String userId, String memoryId) {
        UserLongTermMemory memory = mongoTemplate.findById(memoryId, UserLongTermMemory.class);
        if (memory == null || !userId.equals(memory.getUserId())) return;
        if (memory.getVectorId() != null) embeddingStore.remove(memory.getVectorId());
        mongoTemplate.remove(memory);
    }
}
