package com.ljl.ai.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "trace.logging")
public class TraceLoggingConfig {

    /** 正文可能包含敏感信息，只有显式开启诊断时才记录。 */
    private boolean includeContent = false;

    /** 开启正文记录后的最大长度。 */
    private int maxContentLength = 4096;
}
