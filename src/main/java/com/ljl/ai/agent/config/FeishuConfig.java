package com.ljl.ai.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 飞书API配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "feishu")
public class FeishuConfig {
    
    /**
     * 飞书应用ID
     */
    private String appId;
    
    /**
     * 飞书应用密钥
     */
    private String appSecret;
    
    /**
     * 飞书API基础URL
     */
    private String baseUrl ;
}
