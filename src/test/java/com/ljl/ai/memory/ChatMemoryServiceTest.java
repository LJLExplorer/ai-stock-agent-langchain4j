package com.ljl.ai.memory;

import com.ljl.ai.model.entity.ChatSession;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatMemoryServiceTest {
    @Test
    void shouldRejectSessionOwnedByAnotherUser() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.findById(anyString(), org.mockito.ArgumentMatchers.eq(ChatSession.class)))
                .thenReturn(ChatSession.builder().sessionId("s1").userId("owner").status("ACTIVE").build());
        ChatMemoryService service = new ChatMemoryService();
        ReflectionTestUtils.setField(service, "mongoTemplate", mongo);

        assertThrows(SecurityException.class,
                () -> service.getOrCreateSession("s1", "attacker", null));
    }

    @Test
    void shouldRejectMessagesToClosedSession() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.findById(anyString(), org.mockito.ArgumentMatchers.eq(ChatSession.class)))
                .thenReturn(ChatSession.builder().sessionId("s1").userId("owner").status("CLOSED").build());
        ChatMemoryService service = new ChatMemoryService();
        ReflectionTestUtils.setField(service, "mongoTemplate", mongo);

        assertThrows(IllegalStateException.class,
                () -> service.getOrCreateSession("s1", "owner", null));
    }
}
