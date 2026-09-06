package com.ljl.ai.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "trace.logging")
public class TraceLoggingConfig {

    /** 测试诊断默认记录请求和响应正文；生产环境应设置 TRACE_LOGGING_INCLUDE_CONTENT=false。 */
    private boolean includeContent = true;

    /** 开启正文记录后的最大长度；0 表示不截断。 */
    private int maxContentLength;
}
