package com.ljl.ai.rag;

import com.ljl.ai.config.KnowledgeConfig;
import com.ljl.ai.model.entity.KnowledgeDocument;
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
        when(hybridClient.search(eq("茅台"), any(float[].class), eq(5))).thenReturn(List.of(MilvusHybridSearchResult.builder()
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
        when(hybridClient.search(eq("600519实时行情"), any(float[].class), eq(5))).thenReturn(List.of(
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
}
