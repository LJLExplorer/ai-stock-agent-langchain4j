package com.ljl.ai.observability;

import com.alibaba.fastjson2.JSON;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.List;
import java.util.Set;

/** 记录实际发往模型供应商的请求和响应，包含模型原始工具调用。 */
@Slf4j
public final class TracingChatLanguageModel implements ChatLanguageModel {
    private final ChatLanguageModel delegate;
    private final TraceLoggingConfig config;

    public TracingChatLanguageModel(ChatLanguageModel delegate, TraceLoggingConfig config) {
        this.delegate = delegate;
        this.config = config;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return invoke("chat", request, delegate::chat);
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return invoke("doChat", request, delegate::doChat);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    private ChatResponse invoke(String operation, ChatRequest request, ModelCall call) {
        long started = System.nanoTime();
        String traceId = MDC.get("traceId");
        log.info("model_call_started traceId={}, operation={}, request={}", traceId, operation, contentOf(request));
        try {
            ChatResponse response = call.execute(request);
            log.info("model_call_finished traceId={}, operation={}, elapsedMs={}, response={}", traceId, operation,
                    elapsedMillis(started), contentOf(response));
            return response;
        } catch (RuntimeException exception) {
            log.error("model_call_failed traceId={}, operation={}, elapsedMs={}, request={}", traceId, operation,
                    elapsedMillis(started), contentOf(request), exception);
            throw exception;
        }
    }

    private String contentOf(Object value) {
        String content;
        try {
            content = JSON.toJSONString(value);
        } catch (RuntimeException exception) {
            content = String.valueOf(value);
        }
        int maxLength = config.getMaxContentLength();
        return maxLength > 0 && content.length() > maxLength
                ? content.substring(0, maxLength) + "...<truncated>" : content;
    }

    private long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    @FunctionalInterface
    private interface ModelCall {
        ChatResponse execute(ChatRequest request);
    }
}
