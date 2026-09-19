package com.ljl.ai.service;

import com.ljl.ai.config.MemoryConfig;
import com.ljl.ai.memory.ChatMemoryService;
import com.ljl.ai.model.entity.ChatMessage;
import com.ljl.ai.model.entity.ChatSession;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryBackfillServiceTest {

    @Test
    void shouldEnqueuePersistedUserMessagesAndAdvanceTheCursor() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        MemoryJobService jobs = mock(MemoryJobService.class);
        MemoryConfig config = new MemoryConfig();
        config.getLongTerm().setGenerateEnabled(true);
        ChatMessage message = ChatMessage.builder().messageId("m-1").sessionId("s-1").role("USER")
                .content("以后先说风险").createTime(LocalDateTime.of(2026, 9, 19, 10, 0)).build();
        when(mongo.find(any(Query.class), eq(ChatMessage.class))).thenReturn(List.of(message));
        when(chatMemoryService.getSession("s-1")).thenReturn(ChatSession.builder().userId("u-1").build());

        new MemoryBackfillService(mongo, chatMemoryService, jobs, config).scanOnce();

        verify(jobs).enqueue("u-1", "s-1", "m-1", "以后先说风险",
                LocalDateTime.of(2026, 9, 19, 10, 0));
        verify(mongo).save(argThat((MemoryScanCursor cursor) -> "m-1".equals(cursor.getLastMessageId())));
    }
}
