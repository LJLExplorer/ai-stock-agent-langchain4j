package com.ljl.ai.observability;

import java.time.Instant;
import java.util.Objects;

/** 可回放的工作流运行元数据；刻意不承载 Prompt、响应或工具正文。 */
public record RunEvent(
        String executionId,
        String traceId,
        long sequence,
        Instant occurredAt,
        EventType eventType,
        String node,
        String summary
) {
    public static final int MAX_SUMMARY_LENGTH = 500;

    public RunEvent {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId 不能为空");
        }
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence 必须大于 0");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        eventType = Objects.requireNonNull(eventType, "eventType 不能为空");
        node = node == null ? "" : node.trim();
        summary = summary == null ? "" : summary.trim();
        if (summary.length() > MAX_SUMMARY_LENGTH) {
            throw new IllegalArgumentException("summary 长度不能超过 " + MAX_SUMMARY_LENGTH);
        }
    }

    public enum EventType {
        PLAN_CREATED,
        NODE_STARTED,
        NODE_COMPLETED,
        TOOL_STARTED,
        TOOL_COMPLETED,
        TOOL_FAILED,
        WORKFLOW_RETRYING,
        EVIDENCE_PACK_READY,
        DEEP_RESEARCH_STARTED,
        ROLE_COMPLETED,
        ANSWER_READY,
        WORKFLOW_COMPLETED,
        WORKFLOW_FAILED
    }
}
