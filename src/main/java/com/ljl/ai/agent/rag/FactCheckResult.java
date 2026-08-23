package com.ljl.ai.agent.rag;

import lombok.Builder;
import lombok.Data;

/**
 * 事实核查结果
 */
@Data
@Builder
public class FactCheckResult {
    
    /**
     * 是否事实准确
     */
    private Boolean isFactual;
    
    /**
     * 置信度 0-1
     */
    private Double confidence;
    
    /**
     * 已验证的来源数
     */
    private Integer verifiedSources;
    
    /**
     * 总来源数
     */
    private Integer totalSources;
    
    /**
     * 核查说明
     */
    private String explanation;
}
