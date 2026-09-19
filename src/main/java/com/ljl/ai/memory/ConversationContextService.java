package com.ljl.ai.memory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.agent.QueryRewriteAssistant;
import com.ljl.ai.model.entity.ChatMessage;
import com.ljl.ai.model.entity.UserLongTermMemory;
import com.ljl.ai.service.LongTermMemoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.ljl.ai.support.ModelJsonExtractor.extractJsonObject;

/** 解析当前话题和检索问题，并组合有预算限制的近轮对话、摘要及长期记忆。 */
@Slf4j
@Component
public class ConversationContextService {
    private static final int REWRITE_MESSAGE_LIMIT = 12;
    private static final int FOCUSED_MESSAGE_LIMIT = 8;
    private static final int CONTEXT_CHAR_LIMIT = 6_000;
    private static final int MESSAGE_CHAR_LIMIT = 1_000;
    private static final Pattern LOW_INFORMATION = Pattern.compile(
            "^(好的?|嗯+|谢谢|多谢|收到|明白了?|可以|行|ok|okay)[。.!！?？]*$",
            Pattern.CASE_INSENSITIVE);

    private static final int ROUTING_HISTORY_LIMIT = 30;
    private static final Pattern STOCK_CODE = Pattern.compile("(?<!\\d)(\\d{6})(?:\\.(?:SH|SZ|BJ|HK))?(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    @Resource
    private QueryRewriteAssistant queryRewriteAssistant;

    @Resource
    private ChatMemoryService chatMemoryService;

    @Resource
    private ShortTermSummaryService shortTermSummaryService;

    @Resource
    private ConversationTopicStore conversationTopicStore;

    @Resource
    private LongTermMemoryService longTermMemoryService;

    @Autowired(required = false)
    private MemoryContextAssembler memoryContextAssembler;

    /** 截取最近的非空业务消息，并限制文本长度，供模型补全追问中的主语与时间范围。 */
    public String buildRewriteContext(List<ChatMessage> history) {
        return render(tail(nonBlank(history), REWRITE_MESSAGE_LIMIT), CONTEXT_CHAR_LIMIT);
    }

    /** 去掉寒暄并筛选当前话题相关的近期消息，减少其他标的历史对本轮回答的干扰。 */
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

    public record PreparedContext(String baseMemoryId, String modelMemoryId, String userMessage,
                                  ConversationQuery query, List<ChatMessage> recentHistory) {}

    /**
     * 用当前话题摘要和近期业务历史改写查询，并为选中的话题计算独立模型记忆键。
     * 此处只准备上下文，话题激活留到业务消息保存后执行。
     */
    public PreparedContext prepare(String userId, String sessionId, String originalMessage, String orderId) {
        String baseMemoryId = memoryId(userId, sessionId);
        String userMessage = executionQuestion(originalMessage, orderId);
        ConversationTopicStore.TopicState topicState = currentTopicState(baseMemoryId);
        String activeTopicMemoryId = ConversationTopicStore.topicMemoryId(baseMemoryId, topicState.activeTopicKey());
        String summary = shortTermSummaryService.get(activeTopicMemoryId);
        List<ChatMessage> history = recentHistory(sessionId);
        ConversationQuery query = resolveRetrievalQuery(userMessage, buildRewriteContext(history), summary, topicState);
        String modelMemoryId = ConversationTopicStore.topicMemoryId(baseMemoryId, query.topicKey());
        log.info("chat_retrieval_query_ready traceId={}, sessionId={}, topicKey={}, topicRelation={}, confidence={}, queryLength={}",
                MDC.get("traceId"), sessionId, query.topicKey(), query.topicRelation(),
                query.confidence(), query.standaloneQuery().length());
        return new PreparedContext(baseMemoryId, modelMemoryId, userMessage, query, history);
    }

    /** 以独立查询召回用户长期记忆，并结合当前话题摘要和筛选后的近期原文组装上下文。 */
    public String buildMemoryContext(String userId, PreparedContext context) {
        return buildMemoryContext(userId, context.modelMemoryId(), context.query().standaloneQuery(),
                buildFocusedContext(context.recentHistory(), context.query()));
    }

    public static String memoryId(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    /** 用户问题未包含当前股票时补充标的提示，供计划与查询改写使用，业务历史仍保存用户原文。 */
    public static String executionQuestion(String message, String orderId) {
        String question = StringUtils.defaultString(message);
        String order = StringUtils.trimToEmpty(orderId);
        if (order.isEmpty()) {
            return question;
        }
        Matcher orderCode = STOCK_CODE.matcher(order);
        if (orderCode.find()) {
            String expectedCode = orderCode.group(1);
            Matcher questionCode = STOCK_CODE.matcher(question);
            while (questionCode.find()) {
                if (expectedCode.equals(questionCode.group(1))) {
                    return question;
                }
            }
        } else if (StringUtils.containsIgnoreCase(question, order)) {
            return question;
        }
        return question + "\n当前用户正在咨询股票：" + order;
    }

    public String rewriteRetrievalQuery(String query, String shortTermSummary) {
        return resolveRetrievalQuery(query, "", shortTermSummary,
                ConversationTopicStore.TopicState.empty()).standaloneQuery();
    }

    /**
     * 将追问改写为独立查询并识别话题关系；问题已有股票代码时直接按代码路由，避免模型改错标的。
     * 改写失败或返回空文本时保留原问题，并使用确定性规则补全话题信息。
     */
    public ConversationQuery resolveRetrievalQuery(String query,
                                             String recentConversation,
                                             String shortTermSummary,
                                             ConversationTopicStore.TopicState topicState) {
        if (query == null || query.isBlank()) {
            return new ConversationQuery(query, topicState.activeTopicKey(),
                    ConversationQuery.TopicRelation.CONTINUE, 0D);
        }
        if (STOCK_CODE.matcher(query).find()) {
            log.info("query_rewrite_skipped traceId={}, reason=EXPLICIT_STOCK_CODE", MDC.get("traceId"));
            return fallbackQuery(query, topicState);
        }
        try {
            String rewritten = queryRewriteAssistant.rewrite(
                    query,
                    StringUtils.defaultString(recentConversation),
                    StringUtils.defaultString(shortTermSummary),
                    topicState.promptContext());
            if (StringUtils.isBlank(rewritten)) {
                return fallbackQuery(query, topicState);
            }
            ConversationQuery result = parseResolvedQuery(rewritten, query, topicState);
            return enforceExplicitStockCode(result, query, topicState);
        } catch (Exception exception) {
            log.warn("查询重写失败，使用原始问题检索, errorType={}",
                    exception.getClass().getSimpleName());
            return fallbackQuery(query, topicState);
        }
    }

    public String buildMemoryContext(String userId, String sessionId, String query) {
        return buildMemoryContext(userId, memoryId(userId, sessionId), query, "");
    }

    /** 按近轮原文、话题摘要、用户长期记忆的顺序组装上下文，长期召回故障时保留前两部分。 */
    public String buildMemoryContext(String userId, String modelMemoryId, String query, String focusedContext) {
        List<String> sections = new ArrayList<>();
        if (StringUtils.isNotBlank(focusedContext)) {
            sections.add("【当前话题相关近轮对话】\n" + focusedContext);
        }
        String summary = shortTermSummaryService.get(modelMemoryId);
        if (StringUtils.isNotBlank(summary)) {
            sections.add("【当前话题历史摘要】\n" + summary);
        }
        try {
            List<UserLongTermMemory> core = longTermMemoryService.corePreferences(userId);
            List<UserLongTermMemory> memories = longTermMemoryService.recall(userId, query);
            String longTermContext = renderLongTermMemoryContext(core, memories);
            if (StringUtils.isNotBlank(longTermContext)) sections.add(longTermContext);
        } catch (IllegalArgumentException e) {
            log.warn("长期记忆召回参数非法, errorType={}", e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            log.error("长期记忆召回异常，本轮跳过, userId={}, errorType={}", userId,
                    e.getClass().getSimpleName());
        } catch (Exception e) {
            log.error("长期记忆召回未知异常，本轮跳过, userId={}, errorType={}", userId,
                    e.getClass().getSimpleName());
        }
        return String.join("\n\n", sections);
    }

    /** 兼容旧测试夹具未装配 assembler 的情形，同时让正式路径统一施加记忆预算。 */
    private String renderLongTermMemoryContext(List<UserLongTermMemory> core, List<UserLongTermMemory> related) {
        if (memoryContextAssembler != null) {
            return memoryContextAssembler.assemble(core == null ? List.of() : core,
                    related == null ? List.of() : related);
        }
        List<UserLongTermMemory> values = new ArrayList<>();
        if (core != null) values.addAll(core);
        if (related != null) values.addAll(related);
        if (values.isEmpty()) return "";
        return "【用户长期记忆】\n" + values.stream()
                .filter(java.util.Objects::nonNull)
                .map(memory -> "- " + memory.getContent())
                .collect(Collectors.joining("\n"));
    }

    private ConversationQuery parseResolvedQuery(String raw,
                                                  String originalQuery,
                                                  ConversationTopicStore.TopicState topicState) {
        try {
            JSONObject json = JSON.parseObject(extractJsonObject(raw));
            String standalone = StringUtils.defaultIfBlank(json.getString("standaloneQuery"), originalQuery);
            String topicKey = StringUtils.defaultIfBlank(json.getString("topicKey"), topicState.activeTopicKey());
            ConversationQuery.TopicRelation relation = ConversationQuery.TopicRelation.from(
                    json.getString("topicRelation"));
            double confidence = json.getDoubleValue("confidence");
            return new ConversationQuery(standalone, topicKey, relation, confidence);
        } catch (RuntimeException invalidJson) {
            // 兼容模型偶发只返回改写问句的情况，不让格式问题阻断主链路。
            return new ConversationQuery(raw.trim(), topicState.activeTopicKey(),
                    ConversationQuery.TopicRelation.CONTINUE, 0.5D);
        }
    }

    /** 优先依据原问题、其次依据改写问题中的代码校正话题，并区分新话题、延续、返回和切换。 */
    private ConversationQuery enforceExplicitStockCode(ConversationQuery resolved,
                                                        String originalQuery,
                                                        ConversationTopicStore.TopicState topicState) {
        // 原问题中的代码最可信；若原问题是公司名，也接受改写结果补出的明确代码。
        Matcher matcher = STOCK_CODE.matcher(originalQuery + "\n" + resolved.standaloneQuery());
        if (!matcher.find()) {
            return resolved;
        }
        String explicitTopic = matcher.group(1);
        String active = topicState.activeTopicKey();
        ConversationQuery.TopicRelation relation;
        if (ConversationTopicStore.GENERAL_TOPIC.equals(active)) {
            relation = ConversationQuery.TopicRelation.NEW;
        } else if (active.contains(explicitTopic)) {
            relation = ConversationQuery.TopicRelation.CONTINUE;
        } else if (topicState.topicKeys().stream().anyMatch(topic -> topic.contains(explicitTopic))) {
            relation = ConversationQuery.TopicRelation.RETURN;
        } else {
            relation = ConversationQuery.TopicRelation.SWITCH;
        }
        return new ConversationQuery(resolved.standaloneQuery(), explicitTopic, relation,
                Math.max(resolved.confidence(), 0.9D));
    }

    private ConversationQuery fallbackQuery(String query, ConversationTopicStore.TopicState topicState) {
        ConversationQuery fallback = new ConversationQuery(query, topicState.activeTopicKey(),
                ConversationQuery.TopicRelation.CONTINUE, 0D);
        return enforceExplicitStockCode(fallback, query, topicState);
    }

    private List<ChatMessage> recentHistory(String sessionId) {
        try {
            List<ChatMessage> history = chatMemoryService.getRecentSessionMessages(sessionId, ROUTING_HISTORY_LIMIT);
            return history == null ? List.of() : history;
        } catch (RuntimeException exception) {
            log.warn("读取近期会话用于查询改写失败，本轮仅使用摘要, sessionId={}, errorType={}",
                    sessionId, exception.getClass().getSimpleName());
            return List.of();
        }
    }

    private ConversationTopicStore.TopicState currentTopicState(String baseMemoryId) {
        return conversationTopicStore == null
                ? ConversationTopicStore.TopicState.empty()
                : conversationTopicStore.get(baseMemoryId);
    }

}
