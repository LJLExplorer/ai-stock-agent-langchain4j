package com.ljl.ai.rag;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MilvusHybridSearchResultTest {

    @Test
    void shouldExposeBothChannelScoresAndRrfScore() {
        MilvusHybridSearchResult result = MilvusHybridSearchResult.builder()
                .chunkId("chunk-1")
                .documentId("document-1")
                .content("贵州茅台估值方法")
                .semanticScore(0.88D)
                .bm25Score(12.5D)
                .rrfScore(0.032D)
                .build();

        assertEquals(0.88D, result.getSemanticScore());
        assertEquals(12.5D, result.getBm25Score());
        assertEquals(0.032D, result.getRrfScore());
    }

    @Test
    void shouldUseDenseAndBm25RequestsWithRrfRanking() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        when(milvusClient.hybridSearch(any(HybridSearchReq.class))).thenReturn(SearchResp.builder()
                .searchResults(List.of(List.of(SearchResp.SearchResult.builder()
                        .id("chunk-1")
                        .score(0.032F)
                        .entity(java.util.Map.of("documentId", "document-1", "content", "贵州茅台估值方法"))
                        .build())))
                .build());
        MilvusHybridSearchClient client = new MilvusHybridSearchClient(milvusClient, "knowledge_hybrid", 60);

        List<MilvusHybridSearchResult> results = client.search("贵州茅台", new float[]{0.1F, 0.2F}, 5);

        assertEquals(1, results.size());
        assertEquals(0.032F, results.get(0).getRrfScore());
    }

    @Test
    void shouldReturnHierarchicalMetadataAndRequestAllChildContextFields() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        when(milvusClient.hybridSearch(any(HybridSearchReq.class))).thenReturn(SearchResp.builder()
                .searchResults(List.of(List.of(SearchResp.SearchResult.builder()
                        .id("chunk-1")
                        .score(0.032F)
                        .entity(Map.of(
                                "documentId", "document-1",
                                "content", "贵州茅台估值方法",
                                "ingestionVersion", "version-1",
                                "parentSectionId", "document-1:3",
                                "headingPath", "[\"年报\", \"第三章\"]",
                                "chunkIndex", 2L,
                                "chunkCount", 4L,
                                "stockCode", "600519",
                                "year", 2025L,
                                "tags", "[\"年报\", \"估值\"]"))
                        .build())))
                .build());
        MilvusHybridSearchClient client = new MilvusHybridSearchClient(milvusClient, "knowledge_hybrid", 60);

        List<MilvusHybridSearchResult> results = client.search("贵州茅台", new float[]{0.1F, 0.2F}, 15);

        MilvusHybridSearchResult result = results.getFirst();
        assertEquals("version-1", result.getIngestionVersion());
        assertEquals("document-1:3", result.getParentSectionId());
        assertIterableEquals(List.of("年报", "第三章"), result.getHeadingPath());
        assertEquals(2, result.getChunkIndex());
        assertEquals(4, result.getChunkCount());
        assertEquals("600519", result.getStockCode());
        assertEquals("2025", result.getYear());
        assertIterableEquals(List.of("年报", "估值"), result.getTags());

        ArgumentCaptor<HybridSearchReq> requestCaptor = ArgumentCaptor.forClass(HybridSearchReq.class);
        org.mockito.Mockito.verify(milvusClient).hybridSearch(requestCaptor.capture());
        assertEquals(15, requestCaptor.getValue().getTopK());
        assertIterableEquals(List.of("documentId", "title", "documentType", "source", "content",
                        "ingestionVersion", "parentSectionId", "headingPath", "chunkIndex", "chunkCount",
                        "stockCode", "year", "tags"),
                requestCaptor.getValue().getOutFields());
    }

    @Test
    void shouldUseSafeEmptyValuesForMissingOrMalformedOptionalHierarchicalMetadata() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        when(milvusClient.hybridSearch(any(HybridSearchReq.class))).thenReturn(SearchResp.builder()
                .searchResults(List.of(List.of(SearchResp.SearchResult.builder()
                        .id("chunk-1")
                        .entity(Map.of("documentId", "document-1", "content", "正文",
                                "headingPath", "not-json", "tags", "{not-an-array}",
                                "chunkIndex", "invalid"))
                        .build())))
                .build());
        MilvusHybridSearchClient client = new MilvusHybridSearchClient(milvusClient, "knowledge_hybrid", 60);

        MilvusHybridSearchResult result = client.search("query", new float[]{0.1F}, 1).getFirst();

        assertIterableEquals(List.of(), result.getHeadingPath());
        assertIterableEquals(List.of(), result.getTags());
        assertNull(result.getChunkIndex());
        assertNull(result.getChunkCount());
        assertNull(result.getIngestionVersion());
        assertNull(result.getParentSectionId());
        assertNull(result.getStockCode());
        assertNull(result.getYear());
    }
}
