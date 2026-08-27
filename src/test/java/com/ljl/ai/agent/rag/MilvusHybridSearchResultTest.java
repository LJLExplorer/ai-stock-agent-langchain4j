package com.ljl.ai.agent.rag;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
