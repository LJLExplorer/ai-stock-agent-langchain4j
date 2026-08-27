package com.ljl.ai.agent.rag;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MilvusHybridSearchResult {
    private String chunkId;
    private String documentId;
    private String title;
    private String documentType;
    private String source;
    private String content;
    private Double semanticScore;
    private Double bm25Score;
    private Double rrfScore;
}
