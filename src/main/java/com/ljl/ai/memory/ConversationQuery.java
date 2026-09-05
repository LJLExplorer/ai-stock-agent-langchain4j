package com.ljl.ai.memory;

import org.apache.commons.lang3.StringUtils;

/** 一次多轮问题消解的结构化结果。 */
public record ConversationQuery(String standaloneQuery,
                                String topicKey,
                                TopicRelation topicRelation,
                                double confidence) {

    public ConversationQuery {
        standaloneQuery = StringUtils.defaultString(standaloneQuery).trim();
        topicKey = ConversationTopicStore.normalizeTopicKey(topicKey);
        topicRelation = topicRelation == null ? TopicRelation.CONTINUE : topicRelation;
        confidence = Math.max(0D, Math.min(1D, confidence));
    }

    public enum TopicRelation {
        NEW,
        CONTINUE,
        SWITCH,
        RETURN;

        public static TopicRelation from(String value) {
            try {
                return value == null ? CONTINUE : valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return CONTINUE;
            }
        }
    }
}
