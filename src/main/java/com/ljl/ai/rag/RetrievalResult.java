package com.ljl.ai.rag;


import lombok.Builder;
import lombok.Data;

import java.util.List;

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

    /** 父章节 ID；旧的扁平检索结果可为空。 */
    private String parentSectionId;

    /** 完整章节路径。 */
    private List<String> headingPath;

    /** 长 Parent 的抽取式摘要，不参与 Child Embedding。 */
    private String parentSummary;

    /** 组装此结果时真正命中的 Child ID。 */
    private List<String> matchedChunkIds;

    /** 当前上下文窗口在 Parent 内包含的首个 Child 索引。 */
    private Integer windowStartIndex;

    /** 当前上下文窗口在 Parent 内包含的末个 Child 索引。 */
    private Integer windowEndIndex;
}
