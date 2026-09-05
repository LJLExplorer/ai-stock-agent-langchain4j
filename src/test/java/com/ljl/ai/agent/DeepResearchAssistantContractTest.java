package com.ljl.ai.agent;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeepResearchAssistantContractTest {

    @Test
    void shouldExposeOnlyStatelessRoleAndJudgeMethods() {
        List<String> methods = Arrays.stream(DeepResearchAssistant.class.getDeclaredMethods())
                .map(Method::getName).sorted().toList();

        assertThat(methods).containsExactlyInAnyOrder(
                "fundamental", "technical", "news", "bull", "bear", "risk", "judge");
        Arrays.stream(DeepResearchAssistant.class.getDeclaredMethods()).forEach(method -> {
            assertThat(method.getReturnType()).isEqualTo(String.class);
            assertThat(method.getParameterTypes()).containsExactly(String.class, String.class);
            assertThat(method.getAnnotation(SystemMessage.class)).isNotNull();
            assertThat(method.getAnnotation(UserMessage.class)).isNotNull();
            assertThat(method.getAnnotation(Tool.class)).isNull();
            Arrays.stream(method.getParameterAnnotations()).flatMap(Arrays::stream)
                    .forEach(annotation -> assertThat(annotation.annotationType()).isNotEqualTo(MemoryId.class));
        });
    }
}
