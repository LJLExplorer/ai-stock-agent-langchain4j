package com.ljl.ai.agent.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;

/**
 * 对话请求DTO
 */
@Data
@Builder
public class ChatRequest {
    /**
     * 会话ID（可选，为空则创建新会话）
     */
    private String sessionId;

    /**
     * 用户ID
     */
    @NotBlank(message = "用户ID不能为空")
    private String userId;

    /**
     * 用户消息
     */
    @NotBlank(message = "消息内容不能为空")
    private String message;

    /**
     * 关联订单ID（可选）
     */
    private String orderId;

    /**
     * 是否启用RAG
     */
    private Boolean enableRag = true;

    /**
     * 是否启用工具调用
     */
    private Boolean enableTools = true;
}
