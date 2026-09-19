package com.ljl.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

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
        private int summaryMaxChars = 8_000;
        private long ttl = 86_400;
    }

    @Data
    public static class LongTerm {
        private int topK = 5;
        private double minScore = 0.72;
        /** 新记忆演进链路默认关闭，避免升级后立即向模型发送历史会话。 */
        private boolean generateEnabled = false;
        /** 使用现有 Chat Model 生成结构化候选；默认关闭，未开启时只使用确定性规则。 */
        private boolean modelExtractionEnabled = false;
        private int modelMaxInputChars = 1_000;
        private boolean readEnabled = false;
        /** 为空时所有用户可读；非空时只向名单用户注入长期记忆。 */
        private List<String> readUserAllowlist = List.of();
        private int corePreferenceLimit = 6;
        private int contextMaxChars = 4_000;
        private int jobBatchSize = 20;
        private int jobLeaseSeconds = 60;
        private int jobMaxAttempts = 3;
        private int backfillBatchSize = 100;
        private int maxUserRecallCandidates = 200;
    }
}
