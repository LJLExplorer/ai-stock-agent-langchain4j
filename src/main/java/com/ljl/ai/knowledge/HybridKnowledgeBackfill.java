package com.ljl.ai.knowledge;

import com.ljl.ai.config.KnowledgeConfig;
import com.ljl.ai.model.entity.KnowledgeDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** 将旧的 MongoDB 知识文档回填到新的 Milvus hybrid collection。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridKnowledgeBackfill implements ApplicationRunner {
    private final MongoTemplate mongoTemplate;
    private final MilvusHybridCollectionManager hybridCollectionManager;
    private final KnowledgeConfig knowledgeConfig;
    private final KnowledgeService knowledgeService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            hybridCollectionManager.ensureCollection();
            mongoTemplate.find(new Query(Criteria.where("enabled").is(true)), KnowledgeDocument.class)
                    .forEach(this::backfillSafely);
        } catch (Exception exception) {
            log.error("Milvus Hybrid Search 回填初始化失败，本次启动跳过回填，不影响应用启动", exception);
        }
    }

    private void backfillSafely(KnowledgeDocument document) {
        try {
            backfill(document);
        } catch (Exception exception) {
            log.error("回填知识文档到 Milvus Hybrid Search 失败，跳过该文档, documentId: {}",
                    document.getDocumentId(), exception);
        }
    }

    private void backfill(KnowledgeDocument document) {
        if (document.getRawContent() == null || document.getRawContent().isBlank()) return;
        if (knowledgeConfig.getChunk().getStrategyVersion().equals(document.getChunkingStrategyVersion())) {
            log.debug("知识文档已使用当前分块策略，跳过回填, documentId: {}", document.getDocumentId());
            return;
        }
        knowledgeService.reingestForBackfill(document);
        log.info("已按当前分层策略回填知识文档, documentId: {}", document.getDocumentId());
    }
}
