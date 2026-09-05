package com.ljl.ai.memory;

import com.ljl.ai.config.MemoryConfig;
import com.ljl.ai.agent.ConversationSummaryAssistant;
import dev.langchain4j.data.message.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
            memoryStore.updateMessages(memoryId, messages.subList(split, messages.size()));
            Duration ttl = Duration.ofSeconds(config.getShortTerm().getTtl());
            redis.opsForValue().set(summaryKey(memoryId), summary, ttl);
            redis.opsForValue().set(INDEX_PREFIX + memoryId, Integer.toString(split), ttl);
        } catch (Exception e) {
            try {
                memoryStore.updateMessages(memoryId, messages);
            } catch (Exception rollbackError) {
                log.error("短期记忆摘要失败且回滚消息窗口失败, memoryId: {}", memoryId, rollbackError);
            }
            log.error("短期记忆摘要失败，保留原始窗口, memoryId: {}", memoryId, e);
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
