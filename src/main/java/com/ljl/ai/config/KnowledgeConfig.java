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
         * 分层 Child 最小字符数。
         */
        private int minSize = 600;

        /**
         * 分层 Child 目标字符数。
         */
        private int targetSize = 700;

        /**
         * 分层 Child 最大字符数。
         */
        private int maxSize = 800;

        /**
         * 分层 Child 最小重叠字符数。
         */
        private int minOverlap = 80;

        /**
         * 分层 Child 最大重叠字符数。
         */
        private int maxOverlap = 120;

        /**
         * 小于或等于此长度的 Parent 可直接作为检索上下文返回。
         */
        private int shortParentThreshold = 1200;

        /**
         * 长 Parent 抽取式摘要的最小字符预算。
         */
        private int summaryMinSize = 400;

        /**
         * 长 Parent 抽取式摘要的最大字符预算。
         */
        private int summaryMaxSize = 600;

        /**
         * 当前分层分块策略版本。
         */
        private String strategyVersion = "hierarchical-v1";

        /**
         * 旧通用切分器的分块大小（字符数）。分层切分接入前保留兼容。
         */
        private int size = 500;
        
        /**
         * 旧通用切分器的重叠大小。分层切分接入前保留兼容。
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
