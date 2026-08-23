package com.ljl.ai.agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 处理建议DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SolutionSuggestion {
    
    /**
     * 建议类型
     */
    private String type;
    
    /**
     * 建议标题
     */
    private String title;
    
    /**
     * 详细步骤
     */
    private String steps;
    
    /**
     * 优先级
     */
    private Integer priority;
    
    /**
     * 预估处理时间
     */
    private String estimatedTime;
    
    /**
     * 是否自动执行
     */
    private Boolean autoExecutable;
    
    /**
     * 执行脚本/接口（如可自动执行）
     */
    private String executionScript;
    
    /**
     * 参考链接
     */
    private String referenceUrl;
}
