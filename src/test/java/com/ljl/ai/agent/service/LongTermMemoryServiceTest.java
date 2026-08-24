package com.ljl.ai.agent.service;

import com.ljl.ai.agent.config.MemoryConfig;
import com.ljl.ai.agent.model.entity.UserLongTermMemory;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LongTermMemoryServiceTest {
    @Test
    void shouldVectorizeAndPersistUserOwnedMemory() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        when(embeddingModel.embed(any(TextSegment.class))).thenReturn(Response.from(mock(Embedding.class)));
        when(embeddingStore.add(any(Embedding.class), any(TextSegment.class))).thenReturn("vector-1");
        when(mongoTemplate.save(any(UserLongTermMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LongTermMemoryService service = new LongTermMemoryService(
                embeddingModel, embeddingStore, mongoTemplate, new MemoryConfig());

        UserLongTermMemory memory = service.add("user-1", "偏好新能源行业", null);

        assertEquals("user-1", memory.getUserId());
        assertEquals("vector-1", memory.getVectorId());
        verify(embeddingStore).add(any(Embedding.class), any(TextSegment.class));
        verify(mongoTemplate).save(any(UserLongTermMemory.class));
    }
}
