package com.ljl.ai.agent.rag;

import com.ljl.ai.agent.config.KnowledgeConfig;
import com.ljl.ai.agent.model.entity.KnowledgeSource;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG检索服务 - 负责语义检索和内容增强
 */
@Slf4j
@Service
public class RetrievalService {

    @Autowired
    private EmbeddingModel embeddingModel;
    
    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;
    
    @Autowired
    private KnowledgeConfig knowledgeConfig;

    /**
     * 语义检索相关知识
     */
    public List<RetrievalResult> retrieve(String query) {
        return retrieve(query, knowledgeConfig.getRetrieval().getTopK());
    }

    /**
     * 语义检索相关知识（指定返回数量）
     */
    public List<RetrievalResult> retrieve(String query, int topK) {
        log.info("开始语义检索, query: {}, topK: {}", query, topK);

        // 生成查询向量
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        // 执行向量检索
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore(knowledgeConfig.getRetrieval().getMinScore())
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

        // 转换结果
        List<RetrievalResult> results = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : searchResult.matches()) {
            TextSegment segment = match.embedded();

            RetrievalResult result = RetrievalResult.builder()
                    .content(segment.text())
                    .similarity(match.score())
                    .documentId(segment.metadata().getString("documentId"))
                    .title(segment.metadata().getString("title"))
                    .documentType(segment.metadata().getString("documentType"))
                    .source(segment.metadata().getString("source"))
                    .build();

            results.add(result);
        }

        log.info("语义检索完成, 找到 {} 个相关片段", results.size());
        return results;
    }

    /**
     * 构建增强上下文
     */
    public String buildAugmentedContext(String query, List<RetrievalResult> retrievalResults) {
        if (retrievalResults == null || retrievalResults.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        context.append("【相关知识参考】\n\n");

        for (int i = 0; i < retrievalResults.size(); i++) {
            RetrievalResult result = retrievalResults.get(i);
            context.append(String.format("参考%d（来源：%s，相似度：%.2f）：\n%s\n\n",
                    i + 1,
                    result.getTitle(),
                    result.getSimilarity(),
                    result.getContent()
            ));
        }

        return context.toString();
    }

    /**
     * 将检索结果转换为知识来源
     */
    public List<KnowledgeSource> toKnowledgeSources(List<RetrievalResult> retrievalResults) {
        List<KnowledgeSource> sources = new ArrayList<>();

        for (RetrievalResult result : retrievalResults) {
            KnowledgeSource source = KnowledgeSource.builder()
                    .documentId(result.getDocumentId())
                    .documentTitle(result.getTitle())
                    .documentType(result.getDocumentType())
                    .contentSnippet(truncateContent(result.getContent(), 200))
                    .score(result.getSimilarity())
                    .build();
            sources.add(source);
        }
        return sources;
    }

    /**
     * 截断内容
     */
    private String truncateContent(String content, int maxLength) {
        if (content == null || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }

}
