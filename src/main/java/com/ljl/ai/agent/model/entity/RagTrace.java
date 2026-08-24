package com.ljl.ai.agent.model.entity;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@Document(collection = "rag_traces")
public class RagTrace {
    @Id
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
