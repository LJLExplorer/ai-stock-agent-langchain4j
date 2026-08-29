package com.ljl.ai.model.dto;

import com.ljl.ai.model.entity.KnowledgeSource;
import com.ljl.ai.model.entity.ToolInvocation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话响应DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    
    /**
     * 会话ID
     */
    private String sessionId;
    
    /**
     * 消息ID
     */
    private String messageId;
    
    /**
     * AI回复内容
     */
    private String content;
    
    /**
     * 响应时间
     */
    private LocalDateTime responseTime;
    
    /**
     * 知识来源列表（用于溯源）
     */
    private List<KnowledgeSource> knowledgeSources;
    
    /**
     * 工具调用记录
     */
    private List<ToolInvocation> toolInvocations;
    
    /**
     * 诊断结果（如有）
     */
    
    /**
     * 是否成功
     */
    private Boolean success;
    
    /**
     * 错误信息
     */
    private String errorMessage;
}
