package com.ljl.ai.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "memory")
public class MemoryConfig {
    private ShortTerm shortTerm = new ShortTerm();
    private LongTerm longTerm = new LongTerm();

    @Data
    public static class ShortTerm {
        private int maxMessages = 20;
        private int summaryTriggerMessages = 12;
        private int maxChars = 32_000;
        private long ttl = 86_400;
    }

    @Data
    public static class LongTerm {
        private int topK = 5;
        private double minScore = 0.72;
    }
}
