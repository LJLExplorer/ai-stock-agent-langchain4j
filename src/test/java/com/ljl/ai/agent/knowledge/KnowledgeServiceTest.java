package com.ljl.ai.agent.knowledge;

import com.ljl.ai.agent.config.KnowledgeConfig;
import com.ljl.ai.agent.model.entity.KnowledgeDocument;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;

import static org.mockito.Mockito.*;

class KnowledgeServiceTest {

    @Test
    void shouldRemoveVectorsBeforePersistingDisabledState() {
        FeishuClient feishuClient = mock(FeishuClient.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeConfig knowledgeConfig = new KnowledgeConfig();
        KnowledgeService service = new KnowledgeService(
                feishuClient, embeddingModel, embeddingStore, mongoTemplate, knowledgeConfig);
        KnowledgeDocument document = KnowledgeDocument.builder()
                .documentId("doc-1")
                .enabled(true)
                .vectorIds(List.of("vector-1", "vector-2"))
                .chunkCount(2)
                .build();
        when(mongoTemplate.findOne(any(), eq(KnowledgeDocument.class))).thenReturn(document);

        service.disableDocument("doc-1");

        verify(embeddingStore).remove("vector-1");
        verify(embeddingStore).remove("vector-2");
        verify(mongoTemplate).save(document);
        verifyNoMoreInteractions(embeddingStore);
        org.junit.jupiter.api.Assertions.assertFalse(document.getEnabled());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(), document.getVectorIds());
        org.junit.jupiter.api.Assertions.assertEquals(0, document.getChunkCount());
    }
}
