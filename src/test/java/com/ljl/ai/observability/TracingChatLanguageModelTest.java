package com.ljl.ai.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.langchain4j.model.chat.ChatLanguageModel;
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
    void shouldHideModelRequestAndResponseContentByDefault() {
        ChatLanguageModel delegate = Mockito.mock(ChatLanguageModel.class);
        when(delegate.doChat(null)).thenReturn(null);
        TraceLoggingConfig config = new TraceLoggingConfig();
        TracingChatLanguageModel model = new TracingChatLanguageModel(delegate, config);
        Logger logger = (Logger) LoggerFactory.getLogger(TracingChatLanguageModel.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put("traceId", "trace-model-test");

        try {
            model.doChat(null);
        } finally {
            MDC.clear();
            logger.detachAppender(appender);
        }

        verify(delegate).doChat(null);
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                        .contains("model_call_started", "trace-model-test", "request=<redacted>"))
                .anySatisfy(message -> assertThat(message)
                        .contains("model_call_finished", "trace-model-test", "response=<redacted>"));
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
