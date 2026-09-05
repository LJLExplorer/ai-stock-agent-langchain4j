package com.ljl.ai.model.dto;

import com.ljl.ai.research.AnalysisContext;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 对话请求DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
     * 分析时点（可选，缺省为当前日期）
     */
    private LocalDate analysisDate;

    /**
     * 投研模式（可选，缺省为标准模式）
     */
    private AnalysisContext.ResearchMode researchMode;

    /**
     * 是否启用RAG
     */
    private Boolean enableRag = true;

    /**
     * 是否启用工具调用
     */
    private Boolean enableTools = true;
}
