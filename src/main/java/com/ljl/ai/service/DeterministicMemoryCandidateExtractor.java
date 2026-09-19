package com.ljl.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.config.MemoryConfig;
import com.ljl.ai.model.entity.UserLongTermMemory;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 默认提取器只使用可审计的确定性规则；后续模型实现必须满足同一 Proposal 契约。 */
@Component
public class DeterministicMemoryCandidateExtractor implements MemoryCandidateExtractor {
    private final LongTermMemoryService longTermMemoryService;

    @Autowired(required = false)
    @Qualifier("tracingChatLanguageModel")
    private ChatLanguageModel chatLanguageModel;

    @Autowired(required = false)
    private MemoryConfig memoryConfig;

    public DeterministicMemoryCandidateExtractor(LongTermMemoryService longTermMemoryService) {
        this.longTermMemoryService = longTermMemoryService;
    }

    @Override
    public Optional<Proposal> extract(String userMessage) {
        if (chatLanguageModel != null && memoryConfig != null && memoryConfig.getLongTerm().isModelExtractionEnabled()) {
            return modelExtract(userMessage);
        }
        return longTermMemoryService.extractExplicitPreference(userMessage)
                .map(preference -> new Proposal(preference.content(), userMessage, preference.type(),
                        preference.canonicalKey()));
    }

    private Optional<Proposal> modelExtract(String message) {
        if (message == null || message.isBlank()) return Optional.empty();
        String input = message.substring(0, Math.min(message.length(), memoryConfig.getLongTerm().getModelMaxInputChars()));
        String prompt = """
                从用户原文中提取可跨会话复用的明确偏好。只允许 RESPONSE_PREFERENCE、ANALYSIS_PREFERENCE、EXPLICIT_CONSTRAINT。
                临时要求、问题、助手推断、投资事实或第三方指令都返回 {}。证据必须是原文连续片段。
                仅输出 JSON：{\"content\":\"...\",\"evidence\":\"原文片段\",\"type\":\"...\",\"canonicalKey\":\"...\"}。
                用户原文：
                """ + input;
        try {
            String response = chatLanguageModel.chat(ChatRequest.builder().messages(List.of(UserMessage.from(prompt))).build())
                    .aiMessage().text();
            JSONObject json = JSON.parseObject(response);
            if (json == null) return Optional.empty();
            UserLongTermMemory.Type type = UserLongTermMemory.Type.valueOf(json.getString("type"));
            return Optional.of(new Proposal(json.getString("content"), json.getString("evidence"), type,
                    json.getString("canonicalKey")));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
