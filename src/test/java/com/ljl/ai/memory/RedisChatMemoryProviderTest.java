package com.ljl.ai.memory;

import com.ljl.ai.config.MemoryConfig;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class RedisChatMemoryProviderTest {
    @Test
    void shouldCreateMemoryUsingConfiguredRedisStoreAndScopedMemoryId() {
        RedisChatMemoryStore store = mock(RedisChatMemoryStore.class);
        MemoryConfig config = new MemoryConfig();
        config.getShortTerm().setMaxMessages(8);

        RedisChatMemoryProvider provider = new RedisChatMemoryProvider(store, config);

        ChatMemory memory = provider.get("user-1:session-1");

        assertEquals("user-1:session-1", memory.id());
    }
}
