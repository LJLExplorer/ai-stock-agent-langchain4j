package com.ljl.ai.service;

import com.ljl.ai.model.entity.RagTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
public class RagTraceService {
    public void saveBestEffort(RagTrace trace) {
        try {
            if (trace.getTraceId() == null) trace.setTraceId(UUID.randomUUID().toString());
            if (trace.getCreateTime() == null) trace.setCreateTime(LocalDateTime.now());

            log.info("RAG_DIAGNOSTIC traceId={}, createTime={}, userId={}, sessionId={}, "
                            + "messageId={}, queryLength={}, retrievalCount={}, topScore={}, sourceCount={}, "
                            + "contextLength={}, answerLength={}, factCheckConfidence={}, success={}, hasError={}",
                    trace.getTraceId(), trace.getCreateTime(), trace.getUserId(), trace.getSessionId(),
                    trace.getMessageId(), trace.getQuery() == null ? 0 : trace.getQuery().length(),
                    trace.getRetrievalCount(), trace.getTopScore(),
                    trace.getSourceIds() == null ? 0 : trace.getSourceIds().size(), trace.getContextLength(),
                    trace.getAnswerLength(), trace.getFactCheckConfidence(), trace.getSuccess(),
                    trace.getErrorMessage() != null);
        } catch (Exception e) {
            log.warn("RAG诊断记录写入失败，不影响对话: {}", e.getMessage());
        }
    }
}
