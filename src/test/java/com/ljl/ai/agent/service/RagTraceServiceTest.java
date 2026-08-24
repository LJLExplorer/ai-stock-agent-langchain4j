package com.ljl.ai.agent.service;

import com.ljl.ai.agent.model.entity.RagTrace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagTraceServiceTest {
    @Test
    void shouldLogTraceWithoutMongoDependency() {
        RagTraceService service = new RagTraceService();
        RagTrace trace = RagTrace.builder().query("如何分析贵州茅台").retrievalCount(2).build();

        service.saveBestEffort(trace);

        assertNotNull(trace.getTraceId());
        assertNotNull(trace.getCreateTime());
        assertTrue(trace.getTraceId().length() > 0);
    }
}
