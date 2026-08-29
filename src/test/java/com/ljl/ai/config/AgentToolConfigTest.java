package com.ljl.ai.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentToolConfigTest {
    @Test
    void shouldExposeDefaultMaxSequentialInvocations() {
        AgentToolConfig config = new AgentToolConfig();

        assertEquals(10, config.getMaxSequentialInvocations());
    }
}
