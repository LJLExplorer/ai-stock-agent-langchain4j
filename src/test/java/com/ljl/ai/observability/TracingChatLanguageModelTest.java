package com.ljl.ai.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TracingChatLanguageModelTest {

    @Test
    void nonPositiveContentLimitMustNotEnableUnlimitedLogging() {
        TraceLoggingConfig config = new TraceLoggingConfig();
        config.setIncludeContent(true);
        config.setMaxContentLength(0);
        TracingChatLanguageModel model = new TracingChatLanguageModel(Mockito.mock(ChatLanguageModel.class), config);
        String content = ReflectionTestUtils.invokeMethod(model, "contentOf", "x".repeat(10_000));
        assertThat(content).hasSize(4096 + "...<truncated>".length()).endsWith("...<truncated>");
    }

    @Test
    void shouldLogActualModelRequestAndResponseContentWhenEnabled() {
        ChatLanguageModel delegate = Mockito.mock(ChatLanguageModel.class);
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("测试请求正文")).build();
        ChatResponse response = ChatResponse.builder().aiMessage(AiMessage.from("测试响应正文")).build();
        when(delegate.chat(request)).thenReturn(response);
        TraceLoggingConfig config = new TraceLoggingConfig();
        config.setIncludeContent(true);
        TracingChatLanguageModel model = new TracingChatLanguageModel(delegate, config);
        Logger logger = (Logger) LoggerFactory.getLogger(TracingChatLanguageModel.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put("traceId", "trace-model-test");

        try {
            assertThat(model.chat(request)).isSameAs(response);
        } finally {
            MDC.clear();
            logger.detachAppender(appender);
        }

        verify(delegate).chat(request);
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                        .contains("model_call_started", "trace-model-test", "测试请求正文"))
                .anySatisfy(message -> assertThat(message)
                        .contains("model_call_finished", "trace-model-test", "测试响应正文"));
    }

    @Test
    void shouldAllowDisablingAllContentLogging() {
        TraceLoggingConfig config = new TraceLoggingConfig();
        TracingChatLanguageModel model = new TracingChatLanguageModel(Mockito.mock(ChatLanguageModel.class), config);

        String content = ReflectionTestUtils.invokeMethod(model, "contentOf", "sensitive-model-content");

        assertThat(content).isEqualTo("<redacted>");
    }

    @Test
    void shouldOnlyExposeTruncatedContentWhenExplicitlyEnabled() {
        TraceLoggingConfig config = new TraceLoggingConfig();
        config.setIncludeContent(true);
        config.setMaxContentLength(8);
        TracingChatLanguageModel model = new TracingChatLanguageModel(Mockito.mock(ChatLanguageModel.class), config);

        String content = ReflectionTestUtils.invokeMethod(model, "contentOf", "sensitive-model-content");

        assertThat(content).isEqualTo("\"sensiti...<truncated>");
    }
}
