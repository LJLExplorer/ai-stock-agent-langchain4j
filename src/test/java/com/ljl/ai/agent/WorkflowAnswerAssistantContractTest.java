package com.ljl.ai.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowAnswerAssistantContractTest {

    @Test
    void shouldExposeStatelessGenerateAndRewriteContracts() throws Exception {
        Method generate = WorkflowAnswerAssistant.class.getMethod("generate", String.class, String.class);
        Method rewrite = WorkflowAnswerAssistant.class.getMethod("rewrite", String.class, String.class, String.class);

        assertThat(Arrays.stream(generate.getParameters())
                .noneMatch(parameter -> parameter.isAnnotationPresent(MemoryId.class))).isTrue();
        assertThat(Arrays.stream(rewrite.getParameters())
                .noneMatch(parameter -> parameter.isAnnotationPresent(MemoryId.class))).isTrue();
        assertThat(String.join("\n", generate.getAnnotation(SystemMessage.class).value())).contains("最多 6 列");
        assertThat(String.join("\n", rewrite.getAnnotation(SystemMessage.class).value())).contains("禁止使用表格");
        assertThat(generate.getAnnotation(UserMessage.class)).isNotNull();
        assertThat(rewrite.getAnnotation(UserMessage.class)).isNotNull();
    }

    @Test
    void shouldExposeDedicatedAssistantBeanWithoutChangingToolSelectionApi() throws Exception {
        Method bean = AgentConfig.class.getMethod("workflowAnswerAssistant");

        assertThat(bean.getAnnotation(Bean.class)).isNotNull();
        assertThat(bean.getReturnType()).isEqualTo(WorkflowAnswerAssistant.class);
    }
}
