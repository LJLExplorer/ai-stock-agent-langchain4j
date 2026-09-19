package com.ljl.ai.workflow;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.support.ModelJsonExtractor;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/** 将字段级新闻校验错误交给模型，但只接受服务端白名单内的查询恢复参数。 */
@Component
@Slf4j
public class NewsRecoveryAdvisor {
    @Autowired(required = false)
    @Qualifier("tracingChatLanguageModel")
    private ChatLanguageModel model;

    @Value("${agent.news-recovery.enabled:false}")
    private boolean enabled;

    public NewsRecoveryAdvisor() {
    }

    NewsRecoveryAdvisor(ChatLanguageModel model, boolean enabled) {
        this.model = model;
        this.enabled = enabled;
    }

    public Advice advise(String originalQuery, int currentDays,
                         List<WorkflowResultValidator.ValidationIssue> issues) {
        return advise(originalQuery, currentDays, false, issues);
    }

    public Advice advise(String originalQuery, int currentDays, boolean officialOnly,
                         List<WorkflowResultValidator.ValidationIssue> issues) {
        if (!enabled || issues == null || issues.isEmpty()) return Advice.none();
        if (model == null) return fallback(currentDays, "MODEL_UNAVAILABLE");
        String diagnostics = issues.stream().limit(8)
                .map(issue -> issue.code() + "(" + issue.field() + "):" + issue.message())
                .reduce((left, right) -> left + "；" + right).orElse("");
        String system = """
                你是新闻检索恢复顾问。错误来自服务端协议校验，不得放宽校验。
                只能输出 JSON：{"action":"REFINE_QUERY|NARROW_WINDOW|OFFICIAL_ONLY|INSUFFICIENT_DATA","query":"...","days":1到30}。
                查询必须保持原股票标的；不要输出解释或 Markdown。
                """;
        String input = "原查询：" + safe(originalQuery, 500) + "\n当前窗口：" + currentDays + " 天\n校验错误：" + diagnostics;
        try {
            String raw = model.chat(ChatRequest.builder().messages(List.of(
                    SystemMessage.from(system), UserMessage.from(input))).build()).aiMessage().text();
            JSONObject json = JSON.parseObject(ModelJsonExtractor.extractJsonObject(raw));
            Action action = Action.valueOf(json.getString("action"));
            if (action == Action.INSUFFICIENT_DATA) return new Advice(action, null, currentDays);
            if (action == Action.OFFICIAL_ONLY) {
                if (officialOnly) return fallback(currentDays, "ATTEMPT_DUPLICATED");
                return new Advice(action, originalQuery, currentDays);
            }
            String query = json.getString("query");
            int days = json.getIntValue("days");
            if (query == null || query.isBlank() || query.length() > 500 || days < 1 || days > 30) {
                return fallback(currentDays, "PARAMETER_INVALID");
            }
            if (action == Action.NARROW_WINDOW && days >= currentDays) {
                return fallback(currentDays, "WINDOW_NOT_NARROWED");
            }
            if (query.trim().equals(originalQuery == null ? "" : originalQuery.trim()) && days == currentDays) {
                return fallback(currentDays, "ATTEMPT_DUPLICATED");
            }
            Advice advice = new Advice(action, query.trim(), days);
            log.info("news_recovery_action action={}, days={}", advice.action(), advice.days());
            return advice;
        } catch (RuntimeException exception) {
            return fallback(currentDays, exception.getClass().getSimpleName());
        }
    }

    private Advice fallback(int currentDays, String reason) {
        log.info("news_recovery_fallback action=INSUFFICIENT_DATA, reason={}", reason);
        return new Advice(Action.INSUFFICIENT_DATA, null, currentDays);
    }

    private String safe(String value, int limit) {
        if (value == null) return "";
        return value.substring(0, Math.min(limit, value.length()));
    }

    public enum Action { REFINE_QUERY, NARROW_WINDOW, OFFICIAL_ONLY, INSUFFICIENT_DATA, NONE }
    public record Advice(Action action, String query, int days) {
        static Advice none() { return new Advice(Action.NONE, null, 30); }
    }
}
