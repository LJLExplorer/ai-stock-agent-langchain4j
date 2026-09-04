package com.ljl.ai.knowledge;

import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.client.FeishuClient;
import com.ljl.ai.config.KnowledgeConfig;
import com.ljl.ai.model.entity.KnowledgeDocument;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeServiceTest {

    @Test
    void shouldFindActiveDocumentById() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeService service = service(mock(FeishuClient.class), mock(EmbeddingStore.class), mongoTemplate,
                mock(KnowledgeIngestionService.class));
        KnowledgeDocument document = KnowledgeDocument.builder().documentId("doc-1").rawContent("完整正文").build();
        when(mongoTemplate.findOne(any(), eq(KnowledgeDocument.class))).thenReturn(document);

        assertEquals(document, service.findById("doc-1"));
        verify(mongoTemplate).findOne(any(), eq(KnowledgeDocument.class));
    }

    @Test
    void shouldPublishIngestionVersionOnlyAfterSavingNewDocument() {
        KnowledgeIngestionService ingestionService = mock(KnowledgeIngestionService.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeService service = service(mock(FeishuClient.class), mock(EmbeddingStore.class), mongoTemplate, ingestionService);
        KnowledgeIngestionService.IngestionResult result = result("version-1", "vector-1", 1);
        when(ingestionService.ingest(any(KnowledgeDocument.class))).thenReturn(result);

        KnowledgeDocument document = service.addKnowledgeDocument("新增测试", "正文", "MANUAL", List.of(), java.util.Map.of());

        verify(ingestionService).ingest(document);
        InOrder order = inOrder(ingestionService, mongoTemplate);
        order.verify(ingestionService).ingest(document);
        order.verify(mongoTemplate).save(document);
        assertEquals("version-1", document.getActiveIngestionVersion());
        assertEquals(List.of("vector-1"), document.getVectorIds());
        assertEquals(1, document.getChunkCount());
        assertNull(document.getVersion());
    }

    @Test
    void shouldRollBackIngestionWhenDocumentPublicationFails() {
        KnowledgeIngestionService ingestionService = mock(KnowledgeIngestionService.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeService service = service(mock(FeishuClient.class), mock(EmbeddingStore.class), mongoTemplate, ingestionService);
        KnowledgeIngestionService.IngestionResult result = result("version-1", "vector-1", 1);
        when(ingestionService.ingest(any(KnowledgeDocument.class))).thenReturn(result);
        doThrow(new IllegalStateException("mongo down")).when(mongoTemplate).save(any(KnowledgeDocument.class));

        assertThrows(IllegalStateException.class,
                () -> service.addKnowledgeDocument("新增测试", "正文", "MANUAL", List.of(), java.util.Map.of()));

        verify(ingestionService).rollback(result);
    }

    @Test
    void shouldDelegateFeishuSyncAndPublishItsVersion() {
        FeishuClient feishu = mock(FeishuClient.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeIngestionService ingestionService = mock(KnowledgeIngestionService.class);
        KnowledgeService service = service(feishu, mock(EmbeddingStore.class), mongoTemplate, ingestionService);
        JSONObject meta = new JSONObject();
        meta.put("title", "飞书年报");
        when(feishu.getDocumentContent("token-1")).thenReturn("飞书正文");
        when(feishu.getDocumentMeta("token-1")).thenReturn(meta);
        when(ingestionService.ingest(any(KnowledgeDocument.class))).thenReturn(result("version-sync", "vector-sync", 1));

        KnowledgeDocument document = service.syncFeishuDocument("token-1", "REPORT", List.of("年报"));

        verify(ingestionService).ingest(document);
        assertEquals("version-sync", document.getActiveIngestionVersion());
        assertEquals("hierarchical-v1", document.getChunkingStrategyVersion());
    }

    @Test
    void shouldPersistDisabledStateBeforeRemovingVectors() {
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeService service = service(mock(FeishuClient.class), embeddingStore, mongoTemplate,
                mock(KnowledgeIngestionService.class));
        KnowledgeDocument document = KnowledgeDocument.builder().documentId("doc-1").enabled(true)
                .vectorIds(List.of("vector-1", "vector-2")).chunkCount(2).build();
        when(mongoTemplate.findOne(any(), eq(KnowledgeDocument.class))).thenReturn(document);

        service.disableDocument("doc-1");

        InOrder order = inOrder(mongoTemplate, embeddingStore);
        order.verify(mongoTemplate).save(document);
        order.verify(embeddingStore).remove("vector-1");
        order.verify(embeddingStore).remove("vector-2");
        order.verify(mongoTemplate).save(document);
        assertFalse(document.getEnabled());
        assertEquals(List.of(), document.getVectorIds());
        assertEquals(0, document.getChunkCount());
    }

    @Test
    void shouldDelegateRebuildBeforeEnablingDisabledDocument() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeIngestionService ingestionService = mock(KnowledgeIngestionService.class);
        KnowledgeService service = service(mock(FeishuClient.class), mock(EmbeddingStore.class), mongoTemplate, ingestionService);
        KnowledgeDocument document = KnowledgeDocument.builder().documentId("doc-2").title("启用测试")
                .rawContent("这是一段用于重新生成向量的知识内容。").documentType("MANUAL")
                .source("MANUAL").enabled(false).build();
        when(mongoTemplate.findOne(any(), eq(KnowledgeDocument.class))).thenReturn(document);
        when(ingestionService.ingest(document)).thenReturn(result("version-enable", "vector-new", 1));

        service.enableDocument("doc-2");

        verify(ingestionService).ingest(document);
        verify(mongoTemplate).save(document);
        assertTrue(document.getEnabled());
        assertEquals("version-enable", document.getActiveIngestionVersion());
        assertEquals(List.of("vector-new"), document.getVectorIds());
    }

    @Test
    void shouldNotDeleteVectorsWhenDisablingStateCannotBePersisted() {
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeService service = service(mock(FeishuClient.class), embeddingStore, mongoTemplate,
                mock(KnowledgeIngestionService.class));
        KnowledgeDocument document = KnowledgeDocument.builder().documentId("doc-3").enabled(true)
                .vectorIds(List.of("vector-1")).chunkCount(1).build();
        when(mongoTemplate.findOne(any(), eq(KnowledgeDocument.class))).thenReturn(document);
        doThrow(new RuntimeException("Mongo 不可用")).when(mongoTemplate).save(document);

        assertThrows(RuntimeException.class, () -> service.disableDocument("doc-3"));
        verifyNoInteractions(embeddingStore);
    }

    @Test
    void shouldRollBackNewIngestionWhenEnablePublicationFails() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeIngestionService ingestionService = mock(KnowledgeIngestionService.class);
        KnowledgeService service = service(mock(FeishuClient.class), mock(EmbeddingStore.class), mongoTemplate, ingestionService);
        KnowledgeDocument document = KnowledgeDocument.builder().documentId("doc-4").title("补偿测试")
                .rawContent("重新启用后写入 Mongo 失败时必须删除新向量。").documentType("MANUAL")
                .source("MANUAL").enabled(false).build();
        KnowledgeIngestionService.IngestionResult result = result("version-enable", "vector-new", 1);
        when(mongoTemplate.findOne(any(), eq(KnowledgeDocument.class))).thenReturn(document);
        when(ingestionService.ingest(document)).thenReturn(result);
        doThrow(new RuntimeException("Mongo 不可用")).when(mongoTemplate).save(document);

        assertThrows(RuntimeException.class, () -> service.enableDocument("doc-4"));
        verify(ingestionService).rollback(result);
    }

    @Test
    void shouldCleanPreviousParentAndHybridVersionsAfterPublishingAndIgnoreCleanupFailure() {
        FeishuClient feishu = mock(FeishuClient.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeIngestionService ingestionService = mock(KnowledgeIngestionService.class);
        KnowledgeSectionStore sectionStore = mock(KnowledgeSectionStore.class);
        MilvusHybridCollectionManager hybrid = mock(MilvusHybridCollectionManager.class);
        KnowledgeService service = serviceWithLifecycle(feishu, mock(EmbeddingStore.class), mongoTemplate, ingestionService,
                sectionStore, hybrid);
        KnowledgeDocument existing = KnowledgeDocument.builder().documentId("doc-1").feishuDocToken("token-1")
                .activeIngestionVersion("old-version").vectorIds(List.of("old-vector")).enabled(true).build();
        JSONObject meta = new JSONObject();
        meta.put("title", "更新后的文档");
        when(feishu.getDocumentContent("token-1")).thenReturn("新正文");
        when(feishu.getDocumentMeta("token-1")).thenReturn(meta);
        when(mongoTemplate.findOne(any(), eq(KnowledgeDocument.class))).thenReturn(existing);
        when(ingestionService.ingest(existing)).thenReturn(result("new-version", "new-vector", 1));
        doThrow(new IllegalStateException("section cleanup unavailable"))
                .when(sectionStore).deleteVersion("doc-1", "old-version");

        KnowledgeDocument published = service.syncFeishuDocument("token-1", "REPORT", List.of());

        assertEquals("new-version", published.getActiveIngestionVersion());
        InOrder order = inOrder(mongoTemplate, sectionStore, hybrid);
        order.verify(mongoTemplate).save(existing);
        order.verify(sectionStore).deleteVersion("doc-1", "old-version");
        order.verify(hybrid).deleteDocumentVersion("doc-1", "old-version");
        verify(ingestionService, org.mockito.Mockito.never()).rollback(any());
    }

    @Test
    void shouldCloseVisibilityBarrierBeforeClearingDisabledDocumentParentsAndHybridChildren() {
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeSectionStore sectionStore = mock(KnowledgeSectionStore.class);
        MilvusHybridCollectionManager hybrid = mock(MilvusHybridCollectionManager.class);
        KnowledgeService service = serviceWithLifecycle(mock(FeishuClient.class), embeddingStore, mongoTemplate,
                mock(KnowledgeIngestionService.class), sectionStore, hybrid);
        KnowledgeDocument document = KnowledgeDocument.builder().documentId("doc-disable").enabled(true)
                .vectorIds(List.of("vector-1")).chunkCount(1).build();
        when(mongoTemplate.findOne(any(), eq(KnowledgeDocument.class))).thenReturn(document);

        service.disableDocument("doc-disable");

        InOrder order = inOrder(mongoTemplate, embeddingStore, sectionStore, hybrid);
        order.verify(mongoTemplate).save(document);
        order.verify(embeddingStore).remove("vector-1");
        order.verify(sectionStore).deleteDocument("doc-disable");
        order.verify(hybrid).deleteDocument("doc-disable");
    }

    @Test
    void shouldCloseVisibilityBarrierBeforeDeletingParentsAndHybridChildren() {
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        KnowledgeSectionStore sectionStore = mock(KnowledgeSectionStore.class);
        MilvusHybridCollectionManager hybrid = mock(MilvusHybridCollectionManager.class);
        KnowledgeService service = serviceWithLifecycle(mock(FeishuClient.class), embeddingStore, mongoTemplate,
                mock(KnowledgeIngestionService.class), sectionStore, hybrid);
        KnowledgeDocument document = KnowledgeDocument.builder().documentId("doc-delete").enabled(true)
                .vectorIds(List.of("vector-1")).build();
        when(mongoTemplate.findOne(any(), eq(KnowledgeDocument.class))).thenReturn(document);

        service.deleteDocument("doc-delete");

        InOrder order = inOrder(mongoTemplate, embeddingStore, sectionStore, hybrid);
        order.verify(mongoTemplate).updateFirst(any(), any(), eq(KnowledgeDocument.class));
        order.verify(embeddingStore).remove("vector-1");
        order.verify(sectionStore).deleteDocument("doc-delete");
        order.verify(hybrid).deleteDocument("doc-delete");
        order.verify(mongoTemplate).remove(any(), eq(KnowledgeDocument.class));
    }

    private KnowledgeService service(FeishuClient feishu, EmbeddingStore<TextSegment> embeddingStore,
                                     MongoTemplate mongoTemplate, KnowledgeIngestionService ingestionService) {
        return new KnowledgeService(feishu, embeddingStore, mongoTemplate, new KnowledgeConfig(), ingestionService);
    }

    private KnowledgeService serviceWithLifecycle(FeishuClient feishu, EmbeddingStore<TextSegment> embeddingStore,
                                                  MongoTemplate mongoTemplate, KnowledgeIngestionService ingestionService,
                                                  KnowledgeSectionStore sectionStore,
                                                  MilvusHybridCollectionManager hybrid) {
        KnowledgeService service = service(feishu, embeddingStore, mongoTemplate, ingestionService);
        ReflectionTestUtils.setField(service, "sectionStore", sectionStore);
        ReflectionTestUtils.setField(service, "hybridCollectionManager", hybrid);
        return service;
    }

    private KnowledgeIngestionService.IngestionResult result(String version, String vectorId, int chunkCount) {
        return new KnowledgeIngestionService.IngestionResult("doc-1", version, List.of(vectorId), chunkCount);
    }
}
