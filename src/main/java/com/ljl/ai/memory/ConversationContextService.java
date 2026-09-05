package com.ljl.ai.memory;

import com.ljl.ai.model.entity.ChatMessage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 为查询改写和最终回答构造有边界、受预算限制的会话上下文。 */
@Component
public class ConversationContextService {
    private static final int REWRITE_MESSAGE_LIMIT = 12;
    private static final int FOCUSED_MESSAGE_LIMIT = 8;
    private static final int CONTEXT_CHAR_LIMIT = 6_000;
    private static final int MESSAGE_CHAR_LIMIT = 1_000;
    private static final Pattern LOW_INFORMATION = Pattern.compile(
            "^(好的?|嗯+|谢谢|多谢|收到|明白了?|可以|行|ok|okay)[。.!！?？]*$",
            Pattern.CASE_INSENSITIVE);

    public String buildRewriteContext(List<ChatMessage> history) {
        return render(tail(nonBlank(history), REWRITE_MESSAGE_LIMIT), CONTEXT_CHAR_LIMIT);
    }

    public String buildFocusedContext(List<ChatMessage> history, ConversationQuery query) {
        List<ChatMessage> messages = nonBlank(history).stream()
                .filter(message -> !LOW_INFORMATION.matcher(message.getContent().trim()).matches())
                .toList();
        if (messages.isEmpty()) {
            return "";
        }

        List<ChatMessage> selected;
        String topic = query.topicKey().toLowerCase(Locale.ROOT);
        if (!ConversationTopicStore.GENERAL_TOPIC.equals(topic)) {
            selected = messages.stream()
                    .filter(message -> isRelated(message.getContent(), topic, query.standaloneQuery()))
                    .toList();
            selected = tail(selected, FOCUSED_MESSAGE_LIMIT);
        } else {
            selected = tail(messages, FOCUSED_MESSAGE_LIMIT);
        }
        return render(selected, CONTEXT_CHAR_LIMIT);
    }

    private boolean isRelated(String content, String topic, String standaloneQuery) {
        String normalized = StringUtils.defaultString(content).toLowerCase(Locale.ROOT);
        if (!ConversationTopicStore.GENERAL_TOPIC.equals(topic) && normalized.contains(topic)) {
            return true;
        }
        for (String token : standaloneQuery.toLowerCase(Locale.ROOT).split("[^\\p{IsHan}a-z0-9.]+")) {
            if (token.length() >= 4 && normalized.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private List<ChatMessage> nonBlank(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream()
                .filter(message -> message != null && StringUtils.isNotBlank(message.getContent()))
                .toList();
    }

    private List<ChatMessage> tail(List<ChatMessage> messages, int limit) {
        if (messages.size() <= limit) {
            return messages;
        }
        return messages.subList(messages.size() - limit, messages.size());
    }

    private String render(List<ChatMessage> messages, int maxChars) {
        if (messages.isEmpty()) {
            return "";
        }
        List<String> rendered = new ArrayList<>();
        int length = 0;
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            String content = message.getContent().trim();
            content = content.substring(0, Math.min(content.length(), MESSAGE_CHAR_LIMIT));
            String role = "USER".equalsIgnoreCase(message.getRole()) ? "用户" : "助手";
            String line = role + "：" + content;
            if (!rendered.isEmpty() && length + line.length() > maxChars) {
                break;
            }
            rendered.add(line);
            length += line.length();
        }
        Collections.reverse(rendered);
        return String.join("\n", rendered);
    }
}
