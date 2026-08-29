package com.ljl.ai.rag;


import lombok.Builder;
import lombok.Data;

/**
 * 检索结果
 */
@Data
@Builder
public class RetrievalResult {
    /**
     * 检索到的内容
     */
    private String content;

    /**
     * 相似度得分
     */
    private Double similarity;

    private Double semanticScore;

    private Double bm25Score;

    private Double rrfScore;

    /**
     * 来源文档ID
     */
    private String documentId;

    /**
     * 来源文档标题
     */
    private String title;

    /**
     * 文档类型
     */
    private String documentType;

    /**
     * 来源
     */
    private String source;
}
