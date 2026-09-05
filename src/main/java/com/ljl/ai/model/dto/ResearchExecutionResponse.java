package com.ljl.ai.model.dto;

import java.time.Instant;

/** 深度研究任务被后台执行器接收后的稳定句柄。 */
public record ResearchExecutionResponse(
        String executionId,
        String sessionId,
        Status status,
        Instant submittedAt
) {
    public enum Status {
        ACCEPTED
    }
}
