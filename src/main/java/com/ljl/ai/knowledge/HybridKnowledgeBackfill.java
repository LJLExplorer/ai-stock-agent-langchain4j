package com.ljl.ai.knowledge;

import com.ljl.ai.config.KnowledgeConfig;
import com.ljl.ai.model.entity.KnowledgeDocument;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
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
    private final EmbeddingModel embeddingModel;
    private final MilvusHybridCollectionManager hybridCollectionManager;
    private final KnowledgeConfig knowledgeConfig;

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
        var segments = DocumentSplitters.recursive(knowledgeConfig.getChunk().getSize(), knowledgeConfig.getChunk().getOverlap())
                .split(Document.from(document.getRawContent(), Metadata.from(java.util.Map.of())));
        if (document.getChunkCount() != null && document.getChunkCount() == segments.size()) {
            log.debug("知识文档分块数未变化，跳过重复回填, documentId: {}", document.getDocumentId());
            return;
        }
        hybridCollectionManager.deleteDocument(document.getDocumentId());
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            hybridCollectionManager.insert(document.getDocumentId() + ":" + i, document.getDocumentId(),
                    document.getTitle(), document.getDocumentType(), document.getSource(), segment.text(),
                    embeddingModel.embed(segment).content().vector());
        }
        log.info("已回填知识文档到 Milvus Hybrid Search, documentId: {}, chunks: {}", document.getDocumentId(), segments.size());
    }
}
