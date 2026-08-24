package com.ljl.ai.agent.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class RagTrace {
    private String traceId;
    private String userId;
    private String sessionId;
    private String messageId;
    private String query;
    private Integer retrievalCount;
    private Double topScore;
    private List<String> sourceIds;
    private List<String> sourceTitles;
    private Integer contextLength;
    private Integer answerLength;
    private Double factCheckConfidence;
    private Boolean success;
    private String errorMessage;
    private LocalDateTime createTime;
}
