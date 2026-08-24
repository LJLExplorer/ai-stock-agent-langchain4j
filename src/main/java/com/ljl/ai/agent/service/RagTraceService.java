package com.ljl.ai.agent.service;

import com.ljl.ai.agent.model.entity.RagTrace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagTraceService {
    private final MongoTemplate mongoTemplate;

    public void saveBestEffort(RagTrace trace) {
        try {
            if (trace.getTraceId() == null) trace.setTraceId(UUID.randomUUID().toString());
            if (trace.getCreateTime() == null) trace.setCreateTime(LocalDateTime.now());
            mongoTemplate.save(trace);
        } catch (Exception e) {
            log.warn("RAG诊断记录写入失败，不影响对话: {}", e.getMessage());
        }
    }
}
