package com.ljl.ai.rag;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MilvusHybridSearchResult {
    private String chunkId;
    private String documentId;
    private String title;
    private String documentType;
    private String source;
    private String content;
    private String ingestionVersion;
    private String parentSectionId;
    @Builder.Default
    private List<String> headingPath = List.of();
    private Integer chunkIndex;
    private Integer chunkCount;
    private String stockCode;
    private String year;
    @Builder.Default
    private List<String> tags = List.of();
    private Double semanticScore;
    private Double bm25Score;
    private Double rrfScore;
}
