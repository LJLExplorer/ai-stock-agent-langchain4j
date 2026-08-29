package com.ljl.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus向量数据库配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "milvus")
public class MilvusConfig {
    
    /**
     * Milvus服务地址
     */
    private String host = "localhost";
    
    /**
     * Milvus服务端口
     */
    private int port = 19530;
    
    /**
     * 集合名称
     */
    private String collectionName = "kefu_knowledge_base";

    /** Milvus BM25 + 稠密向量混合检索 collection。 */
    private String hybridCollectionName = "stock_analysis_knowledge_hybrid";

    /**
     * 向量维度
     */
    private int dimension = 1024;

    /**
     * Hybrid Search 失败时是否降级为单路语义检索。
     * 生产环境如需第一时间发现 Milvus 故障，可关闭降级使异常直接抛出。
     */
    private boolean hybridSearchFallbackEnabled = true;
}
