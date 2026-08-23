package com.ljl.ai.agent.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用记录
 */
@Data
@Builder
public class ToolInvocation {
    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 调用参数
     */
    private String parameters;

    /**
     * 调用结果
     */
    private String result;

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 执行耗时(ms)
     */
    private Long executionTime;

    /**
     * 调用时间
     */
    private LocalDateTime invokeTime;
}
