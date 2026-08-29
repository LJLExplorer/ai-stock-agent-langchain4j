package com.ljl.ai.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryConfigTest {
    @Test
    void shouldExposeShortAndLongTermMemorySettings() {
        MemoryConfig config = new MemoryConfig();

        assertEquals(20, config.getShortTerm().getMaxMessages());
        assertEquals(32_000, config.getShortTerm().getMaxChars());
        assertEquals(86_400L, config.getShortTerm().getTtl());
        assertEquals(5, config.getLongTerm().getTopK());
    }
}
