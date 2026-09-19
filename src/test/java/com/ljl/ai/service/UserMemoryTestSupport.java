package com.ljl.ai.service;

import com.ljl.ai.config.MemoryConfig;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class UserMemoryTestSupport {
    private UserMemoryTestSupport() {
    }

    static LongTermMemoryService service(MongoTemplate mongo) {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        when(embeddingModel.embed(any(TextSegment.class))).thenReturn(Response.from(mock(Embedding.class)));
        when(embeddingStore.add(any(Embedding.class), any(TextSegment.class))).thenReturn("vector-1");
        MemoryConfig config = new MemoryConfig();
        config.getLongTerm().setReadEnabled(true);
        return new LongTermMemoryService(embeddingModel, embeddingStore, mongo, config);
    }
}
