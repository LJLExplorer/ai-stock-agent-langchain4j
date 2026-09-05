package com.ljl.ai.memory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.config.MemoryConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 在业务会话下维护当前话题和最近话题，并为模型生成隔离的记忆 ID。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationTopicStore {
    public static final String GENERAL_TOPIC = "general";
    private static final String PREFIX = "ai:memory:topics:";
    private static final int MAX_TOPICS = 5;
    private static final int MAX_TOPIC_KEY_LENGTH = 80;

    private final StringRedisTemplate redis;
    private final MemoryConfig memoryConfig;

    public TopicState get(String baseMemoryId) {
        try {
            String value = redis.opsForValue().get(PREFIX + baseMemoryId);
            if (StringUtils.isBlank(value)) {
                return TopicState.empty();
            }
            JSONObject json = JSON.parseObject(value);
            String active = normalizeTopicKey(json.getString("activeTopicKey"));
            JSONArray storedTopics = json.getJSONArray("topicKeys");
            List<String> topics = new ArrayList<>();
            if (storedTopics != null) {
                for (Object item : storedTopics) {
                    String topic = normalizeTopicKey(String.valueOf(item));
                    if (!GENERAL_TOPIC.equals(topic) && !topics.contains(topic)) {
                        topics.add(topic);
                    }
                }
            }
            return new TopicState(active, List.copyOf(topics));
        } catch (RuntimeException exception) {
            log.warn("读取会话话题状态失败，本轮按通用话题处理, memoryId={}, errorType={}",
                    baseMemoryId, exception.getClass().getSimpleName());
            return TopicState.empty();
        }
    }

    public TopicState activate(String baseMemoryId, String topicKey) {
        TopicState current = get(baseMemoryId);
        String normalized = normalizeTopicKey(topicKey);
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        if (!GENERAL_TOPIC.equals(normalized)) {
            topics.add(normalized);
        }
        topics.addAll(current.topicKeys());
        List<String> limited = topics.stream().limit(MAX_TOPICS).toList();
        TopicState updated = new TopicState(normalized, limited);
        try {
            redis.opsForValue().set(PREFIX + baseMemoryId,
                    JSON.toJSONString(updated),
                    Duration.ofSeconds(memoryConfig.getShortTerm().getTtl()));
        } catch (RuntimeException exception) {
            log.warn("保存会话话题状态失败，本轮继续使用内存结果, memoryId={}, errorType={}",
                    baseMemoryId, exception.getClass().getSimpleName());
        }
        return updated;
    }

    public List<String> modelMemoryIds(String baseMemoryId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(baseMemoryId);
        get(baseMemoryId).topicKeys().stream()
                .map(topic -> topicMemoryId(baseMemoryId, topic))
                .forEach(ids::add);
        return List.copyOf(ids);
    }

    public void delete(String baseMemoryId) {
        redis.delete(PREFIX + baseMemoryId);
    }

    public static String topicMemoryId(String baseMemoryId, String topicKey) {
        String normalized = normalizeTopicKey(topicKey);
        if (GENERAL_TOPIC.equals(normalized)) {
            return baseMemoryId;
        }
        UUID topicId = UUID.nameUUIDFromBytes(normalized.getBytes(StandardCharsets.UTF_8));
        return baseMemoryId + ":topic:" + topicId;
    }

    public static String normalizeTopicKey(String value) {
        if (StringUtils.isBlank(value)) {
            return GENERAL_TOPIC;
        }
        String normalized = value.replaceAll("[\\p{Cntrl}\\r\\n\\t]", " ")
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || GENERAL_TOPIC.equals(normalized)) {
            return GENERAL_TOPIC;
        }
        return normalized.substring(0, Math.min(normalized.length(), MAX_TOPIC_KEY_LENGTH));
    }

    public record TopicState(String activeTopicKey, List<String> topicKeys) {
        public TopicState {
            activeTopicKey = normalizeTopicKey(activeTopicKey);
            topicKeys = topicKeys == null ? List.of() : List.copyOf(topicKeys);
        }

        public static TopicState empty() {
            return new TopicState(GENERAL_TOPIC, List.of());
        }

        public String promptContext() {
            return "当前话题：" + activeTopicKey + "\n最近话题：" + String.join("、", topicKeys);
        }
    }
}
