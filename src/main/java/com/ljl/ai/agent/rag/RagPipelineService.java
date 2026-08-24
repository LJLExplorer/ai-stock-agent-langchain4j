package com.ljl.ai.agent.rag;

import com.ljl.ai.agent.model.entity.KnowledgeSource;
import com.ljl.ai.agent.model.entity.RagTrace;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * RAG管道服务 - 整合检索和生成
 */
@Slf4j
@Service
public class RagPipelineService {

    @Resource
    private RetrievalService retrievalService;

    /**
     * 执行RAG流程
     */
    public RagResult executeRag(String userQuery) {
        log.info("执行RAG流程, query: {}", userQuery);

        // 1. 语义检索
        List<RetrievalResult> retrievalResults = retrievalService.retrieve(userQuery);

        // 2. 构建增强上下文
        String augmentedContext = retrievalService.buildAugmentedContext(userQuery, retrievalResults);

        // 3. 转换知识来源
        List<KnowledgeSource> sources = retrievalService.toKnowledgeSources(retrievalResults);

        // 4. 构建增强后的提示
        String augmentedPrompt = buildAugmentedPrompt(userQuery, augmentedContext);

        return RagResult.builder()
                .originalQuery(userQuery).augmentedPrompt(augmentedPrompt)
                .augmentedContext(augmentedContext).knowledgeSources(sources)
                .retrievalResults(retrievalResults).build();
    }

    public RagTrace buildTrace(String userId, String sessionId, String query, RagResult result) {
        List<RetrievalResult> matches = result.getRetrievalResults();
        return RagTrace.builder()
                .traceId(UUID.randomUUID().toString())
                .userId(userId)
                .sessionId(sessionId)
                .query(query)
                .retrievalCount(matches == null ? 0 : matches.size())
                .topScore(matches == null || matches.isEmpty() ? 0D : matches.get(0).getSimilarity())
                .sourceIds(matches == null ? List.of() : matches.stream().map(RetrievalResult::getDocumentId).toList())
                .sourceTitles(matches == null ? List.of() : matches.stream().map(RetrievalResult::getTitle).toList())
                .contextLength(result.getAugmentedContext() == null ? 0 : result.getAugmentedContext().length())
                .build();
    }

    /**
     * 构建增强后的提示
     */
    private String buildAugmentedPrompt(String userQuery, String augmentedContext) {
        if (augmentedContext == null || augmentedContext.isEmpty()) {
            return userQuery;
        }

        return String.format("""
                请根据以下参考知识回答用户问题。如果参考知识不足以回答问题，请明确告知并基于你的能力提供帮助。
                                
                %s
                                
                用户问题：%s
                                
                请提供准确、有帮助的回答，并在适当时引用参考知识的来源。
                """, augmentedContext, userQuery);
    }

    /**
     * 验证回答与知识库的一致性（幻觉抑制）
     * BUG B004修复: 修复置信度计算逻辑，无检索结果时置信度应为0
     *
     * 置信度计算规则:
     * - 无检索结果: 0.0 (无知识库支持，完全不可信)
     * - 有结果但无匹配: 0.3 (有知识库但回答未引用，可信度低)
     * - 部分匹配: (matchCount / totalCount) * 0.7 + 0.3 (范围: 0.3 ~ 1.0)
     * - 可信阈值: >= 0.6
     */
    public FactCheckResult factCheck(String answer, List<RetrievalResult> retrievalResults) {
        log.info("执行事实核查");

        // 无检索结果 -> 置信度为0（不可信）
        if (retrievalResults == null || retrievalResults.isEmpty()) {
            log.debug("无检索结果，置信度为0");
            return FactCheckResult.builder()
                    .isFactual(false)
                    .confidence(0.0)
                    .verifiedSources(0)
                    .totalSources(0)
                    .build();
        }

        // 统计匹配的知识源
        int matchCount = 0;
        for (RetrievalResult result : retrievalResults) {
            if (answer.contains(result.getTitle()) || containsKeywords(answer, result.getContent())) {
                matchCount++;
            }
        }

        // 置信度计算
        double confidence;
        if (matchCount == 0) {
            // 有知识库但回答未引用 -> 置信度0.3（可信度低）
            confidence = 0.3;
        } else {
            // 部分或完全匹配 -> 置信度 = (匹配比例 * 0.7) + 0.3
            confidence = (double) matchCount / retrievalResults.size() * 0.7 + 0.3;
        }

        boolean isFactual = confidence >= 0.6;  // 置信度阈值: 0.6

        log.info("事实核查完成: 匹配度 {}/{}, 置信度: {:.2f}, 可信: {}",
                matchCount, retrievalResults.size(), confidence, isFactual);

        return FactCheckResult.builder()
                .isFactual(isFactual)
                .confidence(confidence)
                .verifiedSources(matchCount)
                .totalSources(retrievalResults.size())
                .build();
    }

    /**
     * 检查是否包含关键词
     */
    private boolean containsKeywords(String text, String source) {
        // 简单的关键词匹配
        String[] words = source.split("\\s+");
        int matchCount = 0;
        for (String word : words) {
            if (word.length() > 3 && text.contains(word)) {
                matchCount++;
            }
        }
        return matchCount > 3;
    }
}
