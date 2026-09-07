package com.ljl.ai.rag;

import com.ljl.ai.config.KnowledgeConfig;
import com.ljl.ai.config.MilvusConfig;
import com.ljl.ai.model.entity.KnowledgeDocument;
import com.ljl.ai.model.entity.KnowledgeSection;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    @Test
    void shouldReturnMilvusRrfScoreAsFinalSimilarity() {
        RetrievalService service = new RetrievalService();
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MilvusHybridSearchClient hybridClient = mock(MilvusHybridSearchClient.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        when(embeddingModel.embed(any(String.class))).thenReturn(Response.from(new Embedding(new float[]{0.1F, 0.2F})));
        when(hybridClient.search(eq("茅台"), any(float[].class), eq(15))).thenReturn(List.of(MilvusHybridSearchResult.builder()
                .documentId("doc-1").title("估值方法").content("内容").rrfScore(0.031D).build()));
        when(mongoTemplate.find(any(), eq(KnowledgeDocument.class))).thenReturn(List.of(
                KnowledgeDocument.builder().documentId("doc-1").enabled(true).build()));
        ReflectionTestUtils.setField(service, "embeddingModel", embeddingModel);
        ReflectionTestUtils.setField(service, "embeddingStore", embeddingStore);
        ReflectionTestUtils.setField(service, "knowledgeConfig", new KnowledgeConfig());
        ReflectionTestUtils.setField(service, "mongoTemplate", mongoTemplate);
        ReflectionTestUtils.setField(service, "milvusHybridSearchClient", hybridClient);

        RetrievalResult result = service.retrieve("茅台", 5).getFirst();

        assertEquals(0.031D, result.getSimilarity());
        assertEquals(0.031D, result.getRrfScore());
        assertNull(result.getSemanticScore());
        verifyNoInteractions(embeddingStore);
    }

    @Test
    void shouldKeepBm25OnlyMatchesInRrfOrderWithoutDenseVerification() {
        RetrievalService service = new RetrievalService();
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MilvusHybridSearchClient hybridClient = mock(MilvusHybridSearchClient.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        when(embeddingModel.embed(any(String.class))).thenReturn(Response.from(new Embedding(new float[]{0.1F, 0.2F})));
        when(hybridClient.search(eq("600519实时行情"), any(float[].class), eq(15))).thenReturn(List.of(
                MilvusHybridSearchResult.builder().documentId("doc-keyword").title("600519行情字段")
                        .content("600519实时行情字段说明").rrfScore(0.03D).build(),
                MilvusHybridSearchResult.builder().documentId("doc-semantic").title("贵州茅台估值")
                        .content("贵州茅台估值方法").rrfScore(0.02D).build()));
        when(mongoTemplate.find(any(), eq(KnowledgeDocument.class))).thenReturn(List.of(
                KnowledgeDocument.builder().documentId("doc-keyword").enabled(true).build(),
                KnowledgeDocument.builder().documentId("doc-semantic").enabled(true).build()));
        // Dense 只能召回语义文档；精确代码命中仍必须保留，且成功路径不应查询该存储。
        when(embeddingStore.search(any(EmbeddingSearchRequest.class))).thenReturn(
                new EmbeddingSearchResult<>(List.of(semanticMatch("doc-semantic", "贵州茅台估值方法", Map.of("title", "贵州茅台估值")))));
        ReflectionTestUtils.setField(service, "embeddingModel", embeddingModel);
        ReflectionTestUtils.setField(service, "embeddingStore", embeddingStore);
        ReflectionTestUtils.setField(service, "knowledgeConfig", new KnowledgeConfig());
        ReflectionTestUtils.setField(service, "mongoTemplate", mongoTemplate);
        ReflectionTestUtils.setField(service, "milvusHybridSearchClient", hybridClient);

        List<RetrievalResult> results = service.retrieve("600519实时行情", 5);

        assertEquals(List.of("doc-keyword", "doc-semantic"), results.stream().map(RetrievalResult::getDocumentId).toList());
        verifyNoInteractions(embeddingStore);
    }

    @Test
    void shouldUseActiveVersionCandidatesAndAssembleParentContext() {
        RetrievalService service = new RetrievalService();
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MilvusHybridSearchClient hybridClient = mock(MilvusHybridSearchClient.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        when(embeddingModel.embed(any(String.class))).thenReturn(Response.from(new Embedding(new float[]{0.1F, 0.2F})));
        when(hybridClient.search(eq("茅台"), any(float[].class), eq(15))).thenReturn(List.of(
                hybridHit("old-child", "v-old", "过期子块"),
                hybridHit("active-child", "v-active", "当前子块")));
        when(mongoTemplate.find(any(), eq(KnowledgeDocument.class))).thenReturn(List.of(
                KnowledgeDocument.builder().documentId("doc-1").enabled(true)
                        .activeIngestionVersion("v-active").build()));
        when(mongoTemplate.find(any(), eq(KnowledgeSection.class))).thenReturn(List.of(
                KnowledgeSection.builder().sectionId("section-1").documentId("doc-1")
                        .ingestionVersion("v-active").headingPath(List.of("年报", "盈利能力"))
                        .content("完整父章节内容").contentLength(8).build()));
        configure(service, embeddingModel, embeddingStore, mongoTemplate, hybridClient);

        List<RetrievalResult> results = service.retrieve("茅台", 5);

        assertEquals(1, results.size());
        assertEquals("active-child", results.getFirst().getMatchedChunkIds().getFirst());
        assertEquals("标题路径：年报 > 盈利能力\n父章节全文：完整父章节内容", results.getFirst().getContent());
        verifyNoInteractions(embeddingStore);
    }

    @Test
    void shouldFilterStaleSemanticChildrenAndKeepParentMissingChildContext() {
        RetrievalService service = new RetrievalService();
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        when(embeddingModel.embed(any(String.class))).thenReturn(Response.from(new Embedding(new float[]{0.1F, 0.2F})));
        when(mongoTemplate.find(any(), eq(KnowledgeDocument.class))).thenReturn(List.of(
                KnowledgeDocument.builder().documentId("doc-1").enabled(true)
                        .activeIngestionVersion("v-active").build()));
        when(mongoTemplate.find(any(), eq(KnowledgeSection.class))).thenReturn(List.of());
        when(embeddingStore.search(any(EmbeddingSearchRequest.class))).thenReturn(new EmbeddingSearchResult<>(List.of(
                semanticMatch("doc-1", "过期子块", Map.of(
                        "title", "估值", "chunkId", "old-child", "parentSectionId", "section-1",
                        "ingestionVersion", "v-old", "chunkIndex", "0", "headingPath", "年报 > 估值")),
                semanticMatch("doc-1", "当前子块", Map.of(
                        "title", "估值", "chunkId", "active-child", "parentSectionId", "section-1",
                        "ingestionVersion", "v-active", "chunkIndex", "1", "headingPath", "年报 > 估值")))));
        configure(service, embeddingModel, embeddingStore, mongoTemplate, null);

        List<RetrievalResult> results = service.retrieve("茅台", 5);

        assertEquals(1, results.size());
        assertEquals("当前子块", results.getFirst().getContent());
        assertEquals(List.of("年报", "估值"), results.getFirst().getHeadingPath());
        assertEquals("section-1", results.getFirst().getParentSectionId());
    }

    @Test
    void shouldFilterInvisibleDocumentsBeforeApplyingTopK() {
        RetrievalService service = new RetrievalService();
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MilvusHybridSearchClient hybridClient = mock(MilvusHybridSearchClient.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        when(embeddingModel.embed(any(String.class))).thenReturn(Response.from(new Embedding(new float[]{0.1F, 0.2F})));
        when(hybridClient.search(eq("茅台"), any(float[].class), eq(3))).thenReturn(List.of(
                MilvusHybridSearchResult.builder().documentId("doc-invisible").title("不可见文档")
                        .content("已禁用、删除或不存在").rrfScore(0.04D).build(),
                MilvusHybridSearchResult.builder().documentId("doc-1").title("估值")
                        .content("有效关键词命中").rrfScore(0.03D).build(),
                MilvusHybridSearchResult.builder().documentId("doc-2").title("盈利")
                        .content("其他命中").rrfScore(0.02D).build()));
        when(mongoTemplate.find(any(), eq(KnowledgeDocument.class))).thenReturn(List.of(
                KnowledgeDocument.builder().documentId("doc-1").enabled(true).build(),
                KnowledgeDocument.builder().documentId("doc-2").enabled(true).build()));
        configure(service, embeddingModel, embeddingStore, mongoTemplate, hybridClient);

        List<RetrievalResult> results = service.retrieve("茅台", 1);

        assertEquals(List.of("doc-1"), results.stream().map(RetrievalResult::getDocumentId).toList());
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(KnowledgeDocument.class));
        assertEquals(org.bson.Document.parse("""
                {"$and": [
                  {"documentId": {"$in": ["doc-invisible", "doc-1", "doc-2"]}},
                  {"enabled": true},
                  {"$or": [{"deleteStatus": {"$exists": false}}, {"deleteStatus": null}, {"deleteStatus": "ACTIVE"}]}
                ]}
                """), query.getValue().getQueryObject());
        verifyNoInteractions(embeddingStore);
    }

    @Test
    void shouldReturnEmptyHybridResultsWithoutDenseSearch() {
        RetrievalService service = new RetrievalService();
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MilvusHybridSearchClient hybridClient = mock(MilvusHybridSearchClient.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        when(embeddingModel.embed(any(String.class))).thenReturn(Response.from(new Embedding(new float[]{0.1F, 0.2F})));
        when(hybridClient.search(any(), any(float[].class), eq(15))).thenReturn(List.of());
        configure(service, embeddingModel, embeddingStore, mongoTemplate, hybridClient);

        assertTrue(service.retrieve("茅台", 5).isEmpty());

        verifyNoInteractions(embeddingStore, mongoTemplate);
    }

    @Test
    void shouldFallBackToDenseWithMinScoreWhenHybridFails() {
        RetrievalService service = new RetrievalService();
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MilvusHybridSearchClient hybridClient = mock(MilvusHybridSearchClient.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        when(embeddingModel.embed(any(String.class))).thenReturn(Response.from(new Embedding(new float[]{0.1F, 0.2F})));
        when(hybridClient.search(any(), any(float[].class), eq(15))).thenThrow(new IllegalStateException("hybrid unavailable"));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class))).thenReturn(new EmbeddingSearchResult<>(List.of(
                semanticMatch("doc-1", "语义命中", Map.of("title", "估值")))));
        when(mongoTemplate.find(any(), eq(KnowledgeDocument.class))).thenReturn(List.of(
                KnowledgeDocument.builder().documentId("doc-1").enabled(true).build()));
        configure(service, embeddingModel, embeddingStore, mongoTemplate, hybridClient);
        KnowledgeConfig knowledgeConfig = new KnowledgeConfig();
        knowledgeConfig.getRetrieval().setMinScore(0.82D);
        ReflectionTestUtils.setField(service, "knowledgeConfig", knowledgeConfig);

        List<RetrievalResult> results = service.retrieve("茅台", 5);

        assertEquals(List.of("doc-1"), results.stream().map(RetrievalResult::getDocumentId).toList());
        assertEquals(0.9D, results.getFirst().getSimilarity());
        assertNull(results.getFirst().getRrfScore());
        ArgumentCaptor<EmbeddingSearchRequest> request = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(embeddingStore).search(request.capture());
        assertEquals(0.82D, request.getValue().minScore());
        assertEquals(15, request.getValue().maxResults());
    }

    @Test
    void shouldPropagateHybridFailureWhenFallbackDisabled() {
        RetrievalService service = new RetrievalService();
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MilvusHybridSearchClient hybridClient = mock(MilvusHybridSearchClient.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        when(embeddingModel.embed(any(String.class))).thenReturn(Response.from(new Embedding(new float[]{0.1F, 0.2F})));
        IllegalStateException failure = new IllegalStateException("hybrid unavailable");
        when(hybridClient.search(any(), any(float[].class), eq(15))).thenThrow(failure);
        configure(service, embeddingModel, embeddingStore, mongoTemplate, hybridClient);
        MilvusConfig milvusConfig = new MilvusConfig();
        milvusConfig.setHybridSearchFallbackEnabled(false);
        ReflectionTestUtils.setField(service, "milvusConfig", milvusConfig);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.retrieve("茅台", 5)));

        verifyNoInteractions(embeddingStore, mongoTemplate);
    }

    private MilvusHybridSearchResult hybridHit(String chunkId, String ingestionVersion, String content) {
        return MilvusHybridSearchResult.builder().chunkId(chunkId).documentId("doc-1").title("估值")
                .content(content).ingestionVersion(ingestionVersion).parentSectionId("section-1")
                .headingPath(List.of("年报", "盈利能力")).chunkIndex(0).rrfScore(0.03D).build();
    }

    private EmbeddingMatch<TextSegment> semanticMatch(String documentId, String content, Map<String, String> metadata) {
        TextSegment segment = TextSegment.from(content, Metadata.from(mergeDocumentId(documentId, metadata)));
        return new EmbeddingMatch<>(0.9, "vector-" + documentId + content, new Embedding(new float[]{0.1F, 0.2F}), segment);
    }

    private Map<String, String> mergeDocumentId(String documentId, Map<String, String> metadata) {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>(metadata);
        values.put("documentId", documentId);
        return values;
    }

    private void configure(RetrievalService service, EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore,
                           MongoTemplate mongoTemplate, MilvusHybridSearchClient hybridClient) {
        ReflectionTestUtils.setField(service, "embeddingModel", embeddingModel);
        ReflectionTestUtils.setField(service, "embeddingStore", embeddingStore);
        ReflectionTestUtils.setField(service, "knowledgeConfig", new KnowledgeConfig());
        ReflectionTestUtils.setField(service, "milvusConfig", new MilvusConfig());
        ReflectionTestUtils.setField(service, "mongoTemplate", mongoTemplate);
        ReflectionTestUtils.setField(service, "milvusHybridSearchClient", hybridClient);
    }
}
