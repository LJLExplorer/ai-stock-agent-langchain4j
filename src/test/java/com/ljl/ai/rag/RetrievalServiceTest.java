package com.ljl.ai.rag;

import com.ljl.ai.config.KnowledgeConfig;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    private EmbeddingMatch<TextSegment> semanticMatch(String documentId, String content) {
        TextSegment segment = TextSegment.from(content, Metadata.from(Map.of("documentId", documentId)));
        return new EmbeddingMatch<>(0.9, "vector-" + documentId, new Embedding(new float[]{0.1F, 0.2F}), segment);
    }

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
        when(embeddingStore.search(any(EmbeddingSearchRequest.class))).thenReturn(
                new EmbeddingSearchResult<>(List.of(semanticMatch("doc-1", "内容"))));
        ReflectionTestUtils.setField(service, "embeddingModel", embeddingModel);
        ReflectionTestUtils.setField(service, "embeddingStore", embeddingStore);
        ReflectionTestUtils.setField(service, "knowledgeConfig", new KnowledgeConfig());
        ReflectionTestUtils.setField(service, "mongoTemplate", mongoTemplate);
        ReflectionTestUtils.setField(service, "milvusHybridSearchClient", hybridClient);

        RetrievalResult result = service.retrieve("茅台", 5).getFirst();

        assertEquals(0.031D, result.getSimilarity());
        assertEquals(0.031D, result.getRrfScore());
    }

    @Test
    void shouldDropHybridMatchesNotConfirmedBySemanticSearch() {
        // Reproduces the reported bug: an irrelevant document ("白酒好喝") still ranks
        // into the small hybrid candidate pool and gets a plausible-looking RRF score,
        // while a truly relevant document ("贵州茅台估值") also matches. Only the one
        // confirmed by real cosine-similarity search (minScore) should survive.
        RetrievalService service = new RetrievalService();
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MilvusHybridSearchClient hybridClient = mock(MilvusHybridSearchClient.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        when(embeddingModel.embed(any(String.class))).thenReturn(Response.from(new Embedding(new float[]{0.1F, 0.2F})));
        when(hybridClient.search(eq("600519实时行情"), any(float[].class), eq(15))).thenReturn(List.of(
                MilvusHybridSearchResult.builder().documentId("doc-relevant").title("贵州茅台估值")
                        .content("贵州茅台估值方法").rrfScore(0.02D).build(),
                MilvusHybridSearchResult.builder().documentId("doc-noise").title("测试文档111")
                        .content("白酒好喝").rrfScore(0.02D).build()));
        when(mongoTemplate.find(any(), eq(KnowledgeDocument.class))).thenReturn(List.of(
                KnowledgeDocument.builder().documentId("doc-relevant").enabled(true).build(),
                KnowledgeDocument.builder().documentId("doc-noise").enabled(true).build()));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class))).thenReturn(
                new EmbeddingSearchResult<>(List.of(semanticMatch("doc-relevant", "贵州茅台估值方法"))));
        ReflectionTestUtils.setField(service, "embeddingModel", embeddingModel);
        ReflectionTestUtils.setField(service, "embeddingStore", embeddingStore);
        ReflectionTestUtils.setField(service, "knowledgeConfig", new KnowledgeConfig());
        ReflectionTestUtils.setField(service, "mongoTemplate", mongoTemplate);
        ReflectionTestUtils.setField(service, "milvusHybridSearchClient", hybridClient);

        List<RetrievalResult> results = service.retrieve("600519实时行情", 5);

        assertEquals(List.of("doc-relevant"), results.stream().map(RetrievalResult::getDocumentId).toList());
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
        when(embeddingStore.search(any(EmbeddingSearchRequest.class))).thenReturn(new EmbeddingSearchResult<>(List.of(
                semanticMatch("doc-1", "当前子块", Map.of(
                        "chunkId", "active-child", "ingestionVersion", "v-active")))));
        configure(service, embeddingModel, embeddingStore, mongoTemplate, hybridClient);

        List<RetrievalResult> results = service.retrieve("茅台", 5);

        assertEquals(1, results.size());
        assertEquals("active-child", results.getFirst().getMatchedChunkIds().getFirst());
        assertEquals("标题路径：年报 > 盈利能力\n父章节全文：完整父章节内容", results.getFirst().getContent());
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
        ReflectionTestUtils.setField(service, "mongoTemplate", mongoTemplate);
        ReflectionTestUtils.setField(service, "milvusHybridSearchClient", hybridClient);
    }
}
