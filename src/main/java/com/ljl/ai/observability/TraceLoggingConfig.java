package com.ljl.ai.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "trace.logging")
public class TraceLoggingConfig {

    /** 仅在受控诊断环境中显式开启；默认不记录模型请求和响应正文。 */
    private boolean includeContent = false;

    /** 开启正文记录后的最大长度；0 表示不截断。 */
    private int maxContentLength;
}
