package com.ljl.ai.rag;

import com.ljl.ai.config.KnowledgeConfig;
import com.ljl.ai.config.MilvusConfig;
import com.ljl.ai.model.entity.KnowledgeDocument;
import com.ljl.ai.model.entity.KnowledgeSource;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MilvusConfig milvusConfig;

    @Autowired(required = false)
    private MilvusHybridSearchClient milvusHybridSearchClient;

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
        log.info("开始语义检索, queryLength: {}, topK: {}", query == null ? 0 : query.length(), topK);

        // 生成查询向量
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        if (milvusHybridSearchClient != null) {
            try {
                List<MilvusHybridSearchResult> hybridMatches = milvusHybridSearchClient.search(query,
                        queryEmbedding.vector(), topK);
                Set<String> enabledDocumentIds = enabledDocumentIds(hybridMatches.stream()
                        .map(MilvusHybridSearchResult::getDocumentId).filter(value -> !isBlank(value)).toList());
                // RRF 排名分数不代表真实语义相似度：候选池很小时，任何文档都可能"矮子里拔将军"式地
                // 拿到一个数值上说得过去的分数。用同一份 queryEmbedding 再跑一次单路稠密检索，
                // 拿到经 minScore 校验过的真实相关片段集合，作为 hybrid 结果是否保留的依据。
                Set<String> semanticVerifiedKeys = semanticVerifiedContentKeys(queryEmbedding, topK);
                List<RetrievalResult> hybridResults = hybridMatches.stream()
                        .filter(match -> semanticVerifiedKeys.contains(contentKey(match.getDocumentId(), match.getContent())))
                        .filter(match -> enabledDocumentIds.contains(match.getDocumentId()))
                        .map(match -> RetrievalResult.builder()
                                .content(match.getContent()).documentId(match.getDocumentId()).title(match.getTitle())
                                .documentType(match.getDocumentType()).source(match.getSource())
                                .similarity(match.getRrfScore()).semanticScore(match.getSemanticScore())
                                .bm25Score(match.getBm25Score()).rrfScore(match.getRrfScore()).build())
                        .toList();
                log.info("Milvus Hybrid Search完成, 语义校验通过 {} 个RRF融合片段", hybridResults.size());
                return hybridResults;
            } catch (RuntimeException exception) {
                if (!milvusConfig.isHybridSearchFallbackEnabled()) {
                    throw exception;
                }
                log.warn("Milvus Hybrid Search失败，降级为单路语义检索: {}", exception.getMessage());
            }
        }

        // 执行向量检索
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore(knowledgeConfig.getRetrieval().getMinScore())
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

        // 转换结果
        List<RetrievalResult> results = new ArrayList<>();
        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
        Set<String> enabledDocumentIds = enabledDocumentIds(matches.stream()
                .map(EmbeddingMatch::embedded)
                .filter(segment -> segment != null && segment.metadata() != null)
                .map(segment -> segment.metadata().getString("documentId"))
                .filter(value -> !isBlank(value))
                .toList());
        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            if (segment == null || segment.metadata() == null
                    || isBlank(segment.metadata().getString("documentId"))
                    || isBlank(segment.metadata().getString("title"))) {
                log.warn("跳过缺少知识文档元数据的向量命中, score: {}", match.score());
                continue;
            }
            if (!enabledDocumentIds.contains(segment.metadata().getString("documentId"))) {
                log.debug("跳过已禁用或删除中的知识文档向量命中, documentId: {}",
                        segment.metadata().getString("documentId"));
                continue;
            }

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
     * 用真实的稠密向量余弦相似度（已有 minScore 阈值）验证一批候选片段是否真正相关。
     * KnowledgeService 对每个 segment 同时写入 embeddingStore 与 hybrid collection，
     * 两边的 content 文本完全一致，因此可以用 (documentId, content) 作为比对键。
     */
    private Set<String> semanticVerifiedContentKeys(Embedding queryEmbedding, int topK) {
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(Math.max(topK * 4, 20))
                .minScore(knowledgeConfig.getRetrieval().getMinScore())
                .build();
        return embeddingStore.search(searchRequest).matches().stream()
                .map(EmbeddingMatch::embedded)
                .filter(segment -> segment != null)
                .map(segment -> contentKey(segment.metadata() == null ? null : segment.metadata().getString("documentId"),
                        segment.text()))
                .collect(java.util.stream.Collectors.toSet());
    }

    private String contentKey(String documentId, String content) {
        return (documentId == null ? "" : documentId) + " " + (content == null ? "" : content);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Set<String> enabledDocumentIds(Collection<String> documentIds) {
        if (documentIds.isEmpty()) {
            return Set.of();
        }
        Criteria visible = new Criteria().orOperator(
                Criteria.where("deleteStatus").exists(false),
                Criteria.where("deleteStatus").is(null),
                Criteria.where("deleteStatus").is("ACTIVE"));
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("documentId").in(documentIds),
                Criteria.where("enabled").is(true),
                visible));
        return new HashSet<>(mongoTemplate.find(query, KnowledgeDocument.class)
                .stream().map(KnowledgeDocument::getDocumentId).toList());
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
