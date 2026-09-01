package com.ljl.ai.memory;

import com.ljl.ai.model.entity.ChatMessage;
import com.ljl.ai.model.entity.ChatSession;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 会话管理服务
 * 负责会话的创建、查询、管理等功能
 * 业务消息保存在 MongoDB；LangChain4j 的短期消息窗口由 RedisChatMemoryStore 独立维护。
 */
@Slf4j
@Service
public class ChatMemoryService {

    @Resource
    private MongoTemplate mongoTemplate;

    /**
     * 创建新会话
     */
    public ChatSession createSession(String userId, String orderId) {
        ChatSession session = ChatSession.builder()
                .sessionId(UUID.randomUUID().toString())
                .userId(userId)
                .orderId(orderId)
                .status("ACTIVE")
                .createTime(LocalDateTime.now())
                .lastUpdateTime(LocalDateTime.now())
                .messageCount(0)
                .tags(new ArrayList<>())
                .build();

        mongoTemplate.save(session);
        log.info("创建新会话, sessionId: {}, userId: {}", session.getSessionId(), userId);

        return session;
    }

    /**
     * 获取会话
     */
    public ChatSession getSession(String sessionId) {
        return mongoTemplate.findById(sessionId, ChatSession.class);
    }

    /**
     * 获取或创建会话
     */
    public ChatSession getOrCreateSession(String sessionId, String userId, String orderId) {
        if (sessionId != null) {
            ChatSession session = getSession(sessionId);
            if (session != null) {
                if (!userId.equals(session.getUserId())) {
                    throw new SecurityException("无权访问该会话");
                }
                if ("CLOSED".equalsIgnoreCase(session.getStatus())) {
                    throw new IllegalStateException("会话已关闭");
                }
                return session;
            }
        }
        return createSession(userId, orderId);
    }

    /**
     * 获取会话历史消息
     */
    public List<ChatMessage> getSessionMessages(String sessionId) {
        Query query = new Query(Criteria.where("sessionId").is(sessionId))
                .with(Sort.by(Sort.Direction.ASC, "createTime"));
        return mongoTemplate.find(query, ChatMessage.class);
    }

    public List<ChatMessage> getSessionMessages(String sessionId, String userId) {
        ChatSession session = getSession(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new SecurityException("无权访问该会话");
        }
        return getSessionMessages(sessionId);
    }

    /**
     * 获取用户的所有会话
     */
    public List<ChatSession> getUserSessions(String userId) {
        Query query = new Query(Criteria.where("userId").is(userId))
                .with(Sort.by(Sort.Direction.DESC, "lastUpdateTime"));
        return mongoTemplate.find(query, ChatSession.class);
    }

    /**
     * 关闭会话
     */
    public void closeSession(String sessionId) {
        ChatSession session = getSession(sessionId);
        if (session != null) {
            session.setStatus("CLOSED");
            session.setLastUpdateTime(LocalDateTime.now());
            mongoTemplate.save(session);
            log.info("会话已关闭, sessionId: {}", sessionId);
        }
    }

    public void closeSession(String sessionId, String userId) {
        ChatSession session = getSession(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new SecurityException("无权访问该会话");
        }
        closeSession(sessionId);
    }

    /**
     * 更新会话标题
     */
    public void updateSessionTitle(String sessionId, String title) {
        ChatSession session = getSession(sessionId);
        if (session != null && session.getTitle() == null) {
            session.setTitle(title);
            session.setLastUpdateTime(LocalDateTime.now());
            mongoTemplate.save(session);
            log.debug("更新会话标题, sessionId: {}, titleLength: {}", sessionId,
                    title == null ? 0 : title.length());
        }
    }

    /**
     * 保存用户自定义的会话标题。
     */
    public void renameSession(String sessionId, String title) {
        ChatSession session = getSession(sessionId);
        if (session == null) return;
        session.setTitle(title);
        session.setLastUpdateTime(LocalDateTime.now());
        mongoTemplate.save(session);
        log.debug("更新自定义会话标题, sessionId: {}, titleLength: {}", sessionId,
                title == null ? 0 : title.length());
    }

    /**
     * 更新会话摘要
     */
    public void updateSessionSummary(String sessionId, String summary) {
        ChatSession session = getSession(sessionId);
        if (session != null) {
            session.setSummary(summary);
            session.setLastUpdateTime(LocalDateTime.now());
            mongoTemplate.save(session);
        }
    }

    /**
     * 添加会话标签
     */
    public void addSessionTag(String sessionId, String tag) {
        ChatSession session = getSession(sessionId);
        if (session != null && session.getTags() != null) {
            if (!session.getTags().contains(tag)) {
                session.getTags().add(tag);
                mongoTemplate.save(session);
            }
        }
    }

    /**
     * 更新消息反馈
     */
    public void updateMessageFeedback(String messageId, int feedback, String feedbackDetail) {
        Query query = new Query(Criteria.where("messageId").is(messageId));
        ChatMessage message = mongoTemplate.findOne(query, ChatMessage.class);

        if (message != null) {
            message.setFeedback(feedback);
            message.setFeedbackDetail(feedbackDetail);
            mongoTemplate.save(message);
            log.info("消息反馈已更新, messageId: {}, feedback: {}", messageId, feedback);
        }
    }

    /**
     * 删除会话及其消息
     */
    public void deleteSession(String sessionId) {
        // 删除会话的所有消息
        Query messageQuery = new Query(Criteria.where("sessionId").is(sessionId));
        mongoTemplate.remove(messageQuery, ChatMessage.class);

        // 删除会话
        Query sessionQuery = new Query(Criteria.where("sessionId").is(sessionId));
        mongoTemplate.remove(sessionQuery, ChatSession.class);

        log.info("会话已删除, sessionId: {}", sessionId);
    }

    /**
     * 清理空内容的消息
     */
    public long cleanupEmptyContentMessages() {
        Criteria criteria = new Criteria().orOperator(
                Criteria.where("content").is(null),
                Criteria.where("content").is(""),
                Criteria.where("content").exists(false)
        );
        Query query = new Query(criteria);

        long count = mongoTemplate.count(query, ChatMessage.class);
        if (count > 0) {
            log.warn("发现 {} 条空内容消息，准备清理", count);
            mongoTemplate.remove(query, ChatMessage.class);
            log.info("已清理 {} 条空内容消息", count);
        }
        return count;
    }

    /**
     * 获取会话最后一条消息（用于返回响应）
     */
    public ChatMessage getLastMessage(String sessionId) {
        Query query = new Query(Criteria.where("sessionId").is(sessionId))
                .with(Sort.by(Sort.Direction.DESC, "createTime"))
                .limit(1);
        return mongoTemplate.findOne(query, ChatMessage.class);
    }

    /**
     * 保存用户消息
     */
    public ChatMessage saveUserMessage(String sessionId, String content) {
        if (content == null || content.trim().isEmpty()) {
            log.warn("尝试保存空内容的用户消息, sessionId: {}", sessionId);
            return null;
        }
        return saveMessage(sessionId, "USER", content);
    }

    /**
     * 保存AI回复消息
     */
    public ChatMessage saveAssistantMessage(String sessionId, String content) {
        if (content == null || content.trim().isEmpty()) {
            log.warn("尝试保存空内容的AI消息, sessionId: {}", sessionId);
            return null;
        }
        return saveMessage(sessionId, "ASSISTANT", content);
    }

    /**
     * 保存消息到MongoDB
     */
    private ChatMessage saveMessage(String sessionId, String role, String content) {
        ChatMessage message = ChatMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .createTime(LocalDateTime.now())
                .build();

        mongoTemplate.save(message);
        log.debug("保存消息, sessionId: {}, role: {}", sessionId, role);

        // 更新会话统计
        updateSessionStats(sessionId);

        return message;
    }

    /**
     * 更新会话统计信息
     */
    private void updateSessionStats(String sessionId) {
        ChatSession session = getSession(sessionId);
        if (session != null) {
            Query countQuery = new Query(Criteria.where("sessionId").is(sessionId));
            long messageCount = mongoTemplate.count(countQuery, ChatMessage.class);
            session.setMessageCount((int) messageCount);
            session.setLastUpdateTime(LocalDateTime.now());
            mongoTemplate.save(session);
        }
    }
}
