package com.ljl.ai.agent.config;

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
    
    /**
     * 向量维度
     */
    private int dimension = 1024;
}
