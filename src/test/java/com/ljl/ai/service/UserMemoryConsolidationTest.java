package com.ljl.ai.service;

import com.ljl.ai.config.MemoryConfig;
import com.ljl.ai.model.entity.UserLongTermMemory;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryConsolidationTest {

    @Test
    void shouldSupersedeAnOlderExplicitPreferenceForTheSameUserAndKey() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        UserLongTermMemory old = UserLongTermMemory.builder()
                .memoryId("old").userId("user-1").content("以后优先短线分析")
                .canonicalKey("analysis.horizon").memoryScope(UserLongTermMemory.Scope.USER)
                .memoryType(UserLongTermMemory.Type.ANALYSIS_PREFERENCE)
                .status(UserLongTermMemory.Status.ACTIVE).enabled(true).build();
        when(mongo.findOne(any(), org.mockito.ArgumentMatchers.eq(UserLongTermMemory.class))).thenReturn(old);
        when(mongo.save(any(UserLongTermMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LongTermMemoryService service = UserMemoryTestSupport.service(mongo);

        UserLongTermMemory current = service.consolidateExplicitPreference("user-1", "session-1", "message-2",
                "以后优先中长期分析", UserLongTermMemory.Type.ANALYSIS_PREFERENCE, "analysis.horizon");

        assertEquals(UserLongTermMemory.Status.SUPERSEDED, old.getStatus());
        assertFalse(old.getEnabled());
        assertEquals(UserLongTermMemory.Status.ACTIVE, current.getStatus());
        assertEquals("analysis.horizon", current.getCanonicalKey());
        assertEquals(UserLongTermMemory.Origin.AUTO_EXTRACTED, current.getOrigin());
        assertEquals(1, current.getSourceRefs().size());
    }

    @Test
    void shouldNotLetAnOlderMessageSupersedeTheCurrentPreference() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        LocalDateTime newer = LocalDateTime.of(2026, 9, 19, 10, 0);
        UserLongTermMemory current = UserLongTermMemory.builder()
                .memoryId("current").userId("user-1").content("以后优先中长期分析")
                .canonicalValue("以后优先中长期分析").canonicalKey("analysis.horizon")
                .memoryScope(UserLongTermMemory.Scope.USER).status(UserLongTermMemory.Status.ACTIVE).enabled(true)
                .sourceRefs(List.of(UserLongTermMemory.SourceRef.builder().messageId("message-new")
                        .occurredAt(newer).build()))
                .build();
        when(mongo.findOne(any(), org.mockito.ArgumentMatchers.eq(UserLongTermMemory.class))).thenReturn(current);

        LongTermMemoryService service = UserMemoryTestSupport.service(mongo);
        UserLongTermMemory result = service.consolidateExplicitPreference("user-1", "session-1", "message-old",
                "以后优先短线分析", UserLongTermMemory.Type.ANALYSIS_PREFERENCE, "analysis.horizon",
                newer.minusMinutes(1));

        assertEquals(current, result);
        assertEquals(UserLongTermMemory.Status.ACTIVE, current.getStatus());
        verify(mongo, never()).save(any(UserLongTermMemory.class));
    }

    @Test
    void shouldKeepTemporaryInstructionsOutOfDurableMemory() {
        LongTermMemoryService service = UserMemoryTestSupport.service(mock(MongoTemplate.class));

        assertTrue(service.extractExplicitPreference("这次回答简短一些").isEmpty());
    }

    @Test
    void shouldSoftDeleteMemorySoLateVectorHitsCannotBeReadAgain() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        UserLongTermMemory memory = UserLongTermMemory.builder().memoryId("memory-1").userId("user-1")
                .content("以后先说风险").enabled(true).status(UserLongTermMemory.Status.ACTIVE)
                .version(1L).vectorId("vector-1").build();
        when(mongo.findById("memory-1", UserLongTermMemory.class)).thenReturn(memory);
        when(mongo.save(any(UserLongTermMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LongTermMemoryService service = UserMemoryTestSupport.service(mongo);

        service.delete("user-1", "memory-1");

        assertEquals(UserLongTermMemory.Status.DELETED, memory.getStatus());
        assertFalse(memory.getEnabled());
        verify(mongo).save(memory);
    }

    @Test
    void shouldPersistACompensationTaskWhenVectorDeletionFails() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        UserLongTermMemory memory = UserLongTermMemory.builder().memoryId("memory-1").userId("user-1")
                .enabled(true).status(UserLongTermMemory.Status.ACTIVE).version(2L).vectorId("vector-1").build();
        when(mongo.findById("memory-1", UserLongTermMemory.class)).thenReturn(memory);
        when(mongo.save(any(UserLongTermMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new IllegalStateException("vector unavailable")).when(store).remove("vector-1");
        LongTermMemoryService service = new LongTermMemoryService(mock(EmbeddingModel.class), store, mongo,
                new MemoryConfig());

        service.delete("user-1", "memory-1");

        verify(mongo).save(any(MemoryVectorCleanupTask.class));
    }

    @Test
    void shouldRestrictRecallToMongoOwnedMemoriesBeforeSemanticRanking() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        Embedding query = mock(Embedding.class);
        Embedding content = mock(Embedding.class);
        when(query.vector()).thenReturn(new float[]{1F, 0F});
        when(content.vector()).thenReturn(new float[]{1F, 0F});
        when(model.embed("风险偏好")).thenReturn(Response.from(query));
        when(model.embed(any(TextSegment.class))).thenReturn(Response.from(content));
        UserLongTermMemory owned = UserLongTermMemory.builder().memoryId("owned").userId("user-1")
                .content("以后先说风险").enabled(true).status(UserLongTermMemory.Status.ACTIVE).build();
        when(mongo.find(any(), org.mockito.ArgumentMatchers.eq(UserLongTermMemory.class))).thenReturn(List.of(owned));
        MemoryConfig config = new MemoryConfig();
        LongTermMemoryService service = new LongTermMemoryService(model, store, mongo, config);

        List<UserLongTermMemory> result = service.recall("user-1", "风险偏好");

        assertEquals(List.of(owned), result);
        verify(store, never()).search(any());
    }
}
