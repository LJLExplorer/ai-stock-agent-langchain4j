package com.ljl.ai.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 工具调用循环配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent.tool")
public class AgentToolConfig {

    /**
     * 单次对话中允许的连续工具调用次数上限。
     * 模型陷入反复调错工具时，避免空转到 LangChain4j 框架默认的 100 次才报错。
     */
    private int maxSequentialInvocations = 10;
}
