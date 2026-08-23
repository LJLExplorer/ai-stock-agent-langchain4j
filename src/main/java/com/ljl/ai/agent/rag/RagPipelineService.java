package com.ljl.ai.agent.rag;

import com.ljl.ai.agent.model.entity.KnowledgeSource;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

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
     * TODO
     * 验证回答与知识库的一致性（幻觉抑制）
     */
    public FactCheckResult factCheck(String answer, List<RetrievalResult> retrievalResults) {
        log.info("执行事实核查");

        // 简单的关键词匹配验证
        // TODO: 可以接入更复杂的事实核查模型
        int matchCount = 0;
        int totalStatements = 1; // 简化处理

        for (RetrievalResult result : retrievalResults) {
            if (answer.contains(result.getTitle()) || containsKeywords(answer, result.getContent())) {
                matchCount++;
            }
        }

        double confidence = retrievalResults.isEmpty() ? 0.5 : Math.min(1.0, (double) matchCount / retrievalResults.size() + 0.3);

        return FactCheckResult.builder().isFactual(confidence > 0.6).confidence(confidence).verifiedSources(matchCount).totalSources(retrievalResults.size()).build();
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
