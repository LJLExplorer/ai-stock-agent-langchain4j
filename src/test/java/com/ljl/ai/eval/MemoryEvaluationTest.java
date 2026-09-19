package com.ljl.ai.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/** 固定的记忆候选回归集；模型开启后仍应以此类边界样本评估误提与漏提。 */
class MemoryEvaluationTest {
    @Test
    void shouldMeetDeterministicMemoryExtractionBaseline() {
        LongTermMemoryService service = UserMemoryTestSupport.service(mock(MongoTemplate.class));
        Map<String, Boolean> cases = Map.of(
                "以后回答先说风险", true,
                "请记住，之后都简短回答", true,
                "以后优先中长期分析", true,
                "这次回答简短一点", false,
                "你以后能不能简短回答？", false);

        cases.forEach((input, expected) -> assertEquals(expected,
                service.extractExplicitPreference(input).isPresent(), input));
    }
}
