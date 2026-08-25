package com.ljl.ai.agent.knowledge;

import com.ljl.ai.agent.config.KnowledgeConfig;
import com.ljl.ai.agent.model.entity.KnowledgeDocument;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;

import static org.mockito.Mockito.*;

class KnowledgeServiceTest {

    @Test
    void shouldLeaveVersionUnsetWhenAddingNewKnowledgeDocument() {
        FeishuClient feishuClient = mock(FeishuClient.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeService service = new KnowledgeService(
                feishuClient, embeddingModel, embeddingStore, mongoTemplate, new KnowledgeConfig());
        when(embeddingModel.embed(any(TextSegment.class)))
                .thenReturn(Response.from(new Embedding(new float[]{0.1f, 0.2f})));
        when(embeddingStore.add(any(Embedding.class), any(TextSegment.class))).thenReturn("vector-new");

        KnowledgeDocument document = service.addKnowledgeDocument(
                "新增测试", "这是一段新增文档内容。", "MANUAL", List.of(), java.util.Map.of());

        verify(mongoTemplate).save(document);
        org.junit.jupiter.api.Assertions.assertNull(document.getVersion());
    }

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

    @Test
    void shouldRebuildVectorsBeforeEnablingDisabledDocument() {
        FeishuClient feishuClient = mock(FeishuClient.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeService service = new KnowledgeService(
                feishuClient, embeddingModel, embeddingStore, mongoTemplate, new KnowledgeConfig());
        KnowledgeDocument document = KnowledgeDocument.builder()
                .documentId("doc-2")
                .title("启用测试")
                .rawContent("这是一段用于重新生成向量的知识内容。")
                .documentType("MANUAL")
                .source("MANUAL")
                .enabled(false)
                .build();
        when(mongoTemplate.findOne(any(), eq(KnowledgeDocument.class))).thenReturn(document);
        when(embeddingModel.embed(any(TextSegment.class)))
                .thenReturn(Response.from(new Embedding(new float[]{0.1f, 0.2f})));
        when(embeddingStore.add(any(Embedding.class), any(TextSegment.class))).thenReturn("vector-new");

        service.enableDocument("doc-2");

        verify(embeddingStore).add(any(Embedding.class), any(TextSegment.class));
        verify(mongoTemplate).save(document);
        org.junit.jupiter.api.Assertions.assertTrue(document.getEnabled());
        org.junit.jupiter.api.Assertions.assertEquals(List.of("vector-new"), document.getVectorIds());
        org.junit.jupiter.api.Assertions.assertEquals(1, document.getChunkCount());
    }
}
