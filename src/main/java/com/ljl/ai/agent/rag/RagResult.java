package com.ljl.ai.agent.rag;

import com.ljl.ai.agent.model.entity.KnowledgeSource;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * RAG执行结果
 */
@Data
@Builder
public class RagResult {
    /**
     * 原始查询
     */
    private String originalQuery;

    /**
     * 增强后的提示
     */
    private String augmentedPrompt;

    /**
     * 增强上下文
     */
    private String augmentedContext;

    /**
     * 知识来源列表
     */
    private List<KnowledgeSource> knowledgeSources;

    /**
     * 检索结果
     */
    private List<RetrievalResult> retrievalResults;
}
