package com.ljl.ai.agent;

import dev.langchain4j.service.SystemMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentPlannerAssistantTest {

    @Test
    void shouldExposeJsonPlanningMethodWithoutBusinessTools() throws NoSuchMethodException {
        Method plan = AgentPlannerAssistant.class.getMethod("plan", String.class);

        assertEquals(String.class, plan.getReturnType());
        assertNotNull(AgentPlannerAssistant.class.getAnnotation(SystemMessage.class));
        assertEquals(1, AgentPlannerAssistant.class.getDeclaredMethods().length);
    }
}
