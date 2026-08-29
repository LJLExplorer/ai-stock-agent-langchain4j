package com.ljl.ai.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "trace.logging")
public class TraceLoggingConfig {

    /** 0 表示记录完整内容。 */
    private int maxContentLength;
}
