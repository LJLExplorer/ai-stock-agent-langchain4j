package com.ljl.ai.memory;

import com.ljl.ai.config.MemoryConfig;
import com.ljl.ai.agent.ConversationSummaryAssistant;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/** 按字符数压缩 Redis 短期记忆，摘要文本独立于 LangChain4j 消息窗口。 */
@Slf4j
@Service
public class ShortTermSummaryService {
    private static final String SUMMARY_PREFIX = "ai:memory:summary:";
    private static final String INDEX_PREFIX = "ai:memory:summary-index:";

    private final StringRedisTemplate redis;
    private final RedisChatMemoryStore memoryStore;
    private final MemoryConfig config;
    private final BiFunction<String, String, String> summaryGenerator;

    @Autowired
    public ShortTermSummaryService(StringRedisTemplate redis,
                                   RedisChatMemoryStore memoryStore,
                                   MemoryConfig config,
                                   ConversationSummaryAssistant summaryAssistant) {
        this(redis, memoryStore, config,
                (previousSummary, evictedMessages) -> summaryAssistant.summarize(
                        evictedMessages, previousSummary, config.getShortTerm().getSummaryMaxChars()));
    }

    public ShortTermSummaryService(StringRedisTemplate redis,
                                   RedisChatMemoryStore memoryStore,
                                   MemoryConfig config,
                                   BiFunction<String, String, String> summaryGenerator) {
        this.redis = redis;
        this.memoryStore = memoryStore;
        this.config = config;
        this.summaryGenerator = summaryGenerator;
    }

    public String get(String memoryId) {
        return redis.opsForValue().get(summaryKey(memoryId));
    }

    /**
     * 消息数或字符预算触发时，将较早消息与旧摘要递归合并，并保留完整的工具调用与结果组。
     * 新摘要校验通过后才原子提交；窗口已变化则放弃本次压缩，生成失败则保留原文。
     */
    public void refresh(String memoryId) {
        List<ChatMessage> messages = memoryStore.getMessages(memoryId);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        if (config.getShortTerm() == null) {
            log.warn("ShortTerm配置为空, memoryId: {}", memoryId);
            return;
        }
        // 任一预算到达就压缩，避免消息窗口先按 maxMessages 淘汰、旧内容却尚未进入摘要。
        if (messages.size() < config.getShortTerm().getSummaryTriggerMessages()
                && characterCount(messages) <= config.getShortTerm().getMaxChars()) {
            return;
        }

        int split = messages.size() / 2;
        // 工具结果必须与前面的 AI 工具调用一起保留，包括同一轮的多个并行结果。
        // 回退而非向前丢弃结果，避免整组工具交换尚未结束时清空剩余窗口。
        while (split > 0 && messages.get(split) instanceof ToolExecutionResultMessage) {
            split--;
        }
        if (split == 0) {
            return;
        }
        String source = messages.subList(0, split).stream()
                .map(ChatMessage::toString)
                .collect(Collectors.joining("\n"));
        String oldSummary = get(memoryId);
        String summary = summaryGenerator.apply(oldSummary == null ? "" : oldSummary, source);
        int maxSummaryChars = config.getShortTerm().getSummaryMaxChars();
        if (summary == null || summary.isBlank()) {
            throw new IllegalStateException("短期记忆摘要为空");
        }
        if (summary.length() > maxSummaryChars) {
            throw new IllegalStateException("短期记忆摘要超过字符上限");
        }

        try {
            if (!memoryStore.compact(memoryId, messages, split, oldSummary, summary)) {
                log.debug("短期记忆窗口已变化，跳过本次摘要压缩, memoryId: {}", memoryId);
            }
        } catch (Exception e) {
            log.error("短期记忆摘要原子提交失败, memoryId: {}", memoryId, e);
            throw new IllegalStateException("短期记忆摘要失败", e);
        }
    }

    public void delete(String memoryId) {
        redis.delete(summaryKey(memoryId));
        redis.delete(INDEX_PREFIX + memoryId);
    }

    private int characterCount(List<ChatMessage> messages) {
        return messages.stream().mapToInt(message -> message.toString().length()).sum();
    }

    private String summaryKey(String memoryId) {
        return SUMMARY_PREFIX + memoryId;
    }
}
