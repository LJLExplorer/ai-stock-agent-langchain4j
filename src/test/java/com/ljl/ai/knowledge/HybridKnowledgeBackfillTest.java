package com.ljl.ai.knowledge;

import com.ljl.ai.config.KnowledgeConfig;
import com.ljl.ai.model.entity.KnowledgeDocument;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HybridKnowledgeBackfillTest {

    @Test
    void shouldReingestDocumentsWhoseStrategyIsOutdated() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MilvusHybridCollectionManager hybrid = mock(MilvusHybridCollectionManager.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        KnowledgeDocument legacy = document("legacy", "legacy-v0");
        when(mongoTemplate.find(any(), eq(KnowledgeDocument.class))).thenReturn(List.of(legacy));

        new HybridKnowledgeBackfill(mongoTemplate, hybrid, new KnowledgeConfig(), knowledgeService)
                .run(new DefaultApplicationArguments());

        verify(hybrid).ensureCollection();
        verify(knowledgeService).reingestForBackfill(legacy);
    }

    @Test
    void shouldSkipDocumentsAlreadyUsingCurrentStrategy() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MilvusHybridCollectionManager hybrid = mock(MilvusHybridCollectionManager.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        KnowledgeDocument current = document("current", "hierarchical-v1");
        when(mongoTemplate.find(any(), eq(KnowledgeDocument.class))).thenReturn(List.of(current));

        new HybridKnowledgeBackfill(mongoTemplate, hybrid, new KnowledgeConfig(), knowledgeService)
                .run(new DefaultApplicationArguments());

        verifyNoInteractions(knowledgeService);
    }

    @Test
    void shouldContinueWhenOneDocumentBackfillFails() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MilvusHybridCollectionManager hybrid = mock(MilvusHybridCollectionManager.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        KnowledgeDocument broken = document("broken", "legacy-v0");
        KnowledgeDocument later = document("later", "legacy-v0");
        when(mongoTemplate.find(any(), eq(KnowledgeDocument.class))).thenReturn(List.of(broken, later));
        doThrow(new IllegalStateException("broken document")).when(knowledgeService).reingestForBackfill(broken);

        new HybridKnowledgeBackfill(mongoTemplate, hybrid, new KnowledgeConfig(), knowledgeService)
                .run(new DefaultApplicationArguments());

        verify(knowledgeService).reingestForBackfill(later);
    }

    private KnowledgeDocument document(String documentId, String strategyVersion) {
        return KnowledgeDocument.builder().documentId(documentId).title(documentId).rawContent("正文")
                .enabled(true).chunkingStrategyVersion(strategyVersion).build();
    }
}
