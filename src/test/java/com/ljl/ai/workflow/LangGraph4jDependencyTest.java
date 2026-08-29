package com.ljl.ai.workflow;

import org.bsc.langgraph4j.StateGraph;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class LangGraph4jDependencyTest {

    @Test
    void shouldExposeStateGraphApi() {
        assertNotNull(StateGraph.class);
    }
}
