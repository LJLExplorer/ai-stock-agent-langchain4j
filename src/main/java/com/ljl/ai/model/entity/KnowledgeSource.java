package com.ljl.ai.model.entity;

import lombok.Builder;
import lombok.Data;

/**
 * 知识来源 - 用于答案溯源
 */
@Data
@Builder
public class KnowledgeSource {
    /**
     * 文档ID
     */
    private String documentId;

    /**
     * 文档标题
     */
    private String documentTitle;

    /**
     * 文档类型
     * FEISHU_DOC-飞书文档, PRD-产品需求文档,
     * TECH_DOC-技术文档, SOP-标准操作流程,
     * FAQ-常见问题, POLICY-政策规则
     */
    private String documentType;

    /**
     * 匹配内容片段
     */
    private String contentSnippet;

    /**
     * 相似度得分
     */
    private Double score;

    /**
     * 文档链接
     */
    private String documentUrl;

    /**
     * 来源位置（页码/章节）
     */
    private String location;
}
