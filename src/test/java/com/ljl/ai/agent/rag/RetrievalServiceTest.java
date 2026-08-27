package com.ljl.ai.agent.rag;

import com.ljl.ai.agent.config.KnowledgeConfig;
import com.ljl.ai.agent.model.entity.KnowledgeDocument;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {
    @Test
    void shouldReturnMilvusRrfScoreAsFinalSimilarity() {
        RetrievalService service = new RetrievalService();
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MilvusHybridSearchClient hybridClient = mock(MilvusHybridSearchClient.class);
        when(embeddingModel.embed(any(String.class))).thenReturn(Response.from(new Embedding(new float[]{0.1F, 0.2F})));
        when(hybridClient.search(eq("茅台"), any(float[].class), eq(5))).thenReturn(List.of(MilvusHybridSearchResult.builder()
                .documentId("doc-1").title("估值方法").content("内容").rrfScore(0.031D).build()));
        when(mongoTemplate.find(any(), eq(KnowledgeDocument.class))).thenReturn(List.of(
                KnowledgeDocument.builder().documentId("doc-1").enabled(true).build()));
        ReflectionTestUtils.setField(service, "embeddingModel", embeddingModel);
        ReflectionTestUtils.setField(service, "embeddingStore", mock(EmbeddingStore.class));
        ReflectionTestUtils.setField(service, "knowledgeConfig", new KnowledgeConfig());
        ReflectionTestUtils.setField(service, "mongoTemplate", mongoTemplate);
        ReflectionTestUtils.setField(service, "milvusHybridSearchClient", hybridClient);

        RetrievalResult result = service.retrieve("茅台", 5).getFirst();

        assertEquals(0.031D, result.getSimilarity());
        assertEquals(0.031D, result.getRrfScore());
    }
}
