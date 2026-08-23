package com.ljl.ai.agent.model.entity;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话消息实体 - MongoDB存储
 */
@Data
@Builder
@Document(collection = "chat_messages")
public class ChatMessage {
    
    /**
     * 消息ID
     */
    @Id
    private String messageId;
    
    /**
     * 会话ID
     */
    private String sessionId;
    
    /**
     * 消息角色
     * USER-用户, ASSISTANT-AI助手, SYSTEM-系统
     */
    private String role;
    
    /**
     * 消息内容
     */
    private String content;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * Token消耗数
     */
    private Integer tokenCount;
    
    /**
     * 知识来源（用于溯源）
     */
    private List<KnowledgeSource> knowledgeSources;
    
    /**
     * 工具调用记录
     */
    private List<ToolInvocation> toolInvocations;
    
    /**
     * 用户反馈 1-有帮助 0-无帮助 -1-错误
     */
    private Integer feedback;
    
    /**
     * 反馈详情
     */
    private String feedbackDetail;
}
