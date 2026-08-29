package com.ljl.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "knowledge")
public class KnowledgeConfig {
    
    /**
     * 分块配置
     */
    private ChunkConfig chunk = new ChunkConfig();
    
    /**
     * 检索配置
     */
    private RetrievalConfig retrieval = new RetrievalConfig();
    
    @Data
    public static class ChunkConfig {
        /**
         * 分块大小（字符数）
         */
        private int size = 500;
        
        /**
         * 重叠大小
         */
        private int overlap = 50;
    }
    
    @Data
    public static class RetrievalConfig {
        /**
         * 检索结果数量
         */
        private int topK = 5;
        
        /**
         * 最小相似度得分
         */
        private double minScore = 0.7;

    }
}
