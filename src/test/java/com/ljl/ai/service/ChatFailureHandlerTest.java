package com.ljl.ai.service;

import com.ljl.ai.memory.RedisChatMemoryProvider;
import com.ljl.ai.model.dto.ChatRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatFailureHandlerTest {

    @Test
    void shouldExplainDisabledModelInsteadOfReturningGenericFailure() {
        ChatFailureHandler handler = new ChatFailureHandler();
        ReflectionTestUtils.setField(handler, "chatMemoryProvider", mock(RedisChatMemoryProvider.class));

        var response = handler.handle(new IllegalStateException("400: 模型已停用，请选择其他模型"),
                ChatRequest.builder().userId("user").message("分析").build(), null, null);

        assertThat(response.getContent()).contains("模型已停用", "OPENAI_MODEL_NAME");
        assertThat(response.getSuccess()).isFalse();
    }
}
