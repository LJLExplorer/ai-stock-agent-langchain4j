package com.ljl.ai.agent.service;

import com.ljl.ai.agent.model.entity.RagTrace;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.mockito.Mockito.*;

class RagTraceServiceTest {
    @Test
    void shouldIgnoreMongoFailureWhenWritingLightweightTrace() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.save(any(RagTrace.class))).thenThrow(new RuntimeException("mongo unavailable"));
        RagTraceService service = new RagTraceService(mongoTemplate);

        service.saveBestEffort(RagTrace.builder().traceId("trace-1").retrievalCount(0).build());

        verify(mongoTemplate).save(any(RagTrace.class));
    }
}
