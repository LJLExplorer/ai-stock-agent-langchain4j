package com.ljl.ai.agent.model.entity;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话会话实体 - MongoDB存储
 */
@Data
@Builder
@Document(collection = "chat_sessions")
public class ChatSession {
    /**
     * 会话ID
     */
    @Id
    private String sessionId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 关联订单ID（可选）
     */
    private String orderId;

    /**
     * 会话状态
     * ACTIVE-进行中, CLOSED-已结束
     */
    private String status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 最后更新时间
     */
    private LocalDateTime lastUpdateTime;

    /**
     * 消息数量
     */
    private Integer messageCount;

    /**
     * 会话摘要
     */
    private String summary;

    /**
     * 标签
     */
    private List<String> tags;
}
