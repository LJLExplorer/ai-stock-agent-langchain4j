package com.ljl.ai;

import com.ljl.ai.config.MilvusConfig;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Milvus 向量数据库连接与基本操作测试
 */
@SpringBootTest(classes = StockAnalysisAgentApplication.class)
public class MilvusConnectionIT {

    @Resource
    private MilvusConfig milvusConfig;

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Resource
    private EmbeddingModel embeddingModel;

    /**
     * 测试 Milvus 配置是否正确加载
     */
    @Test
    public void testMilvusConfig() {
        assertNotNull(milvusConfig, "MilvusConfig 应该被正确注入");
        assertNotNull(milvusConfig.getHost(), "Milvus Host 不应为空");
        System.out.println("Milvus 配置验证成功:");
        System.out.println("Host: " + milvusConfig.getHost());
        System.out.println("Port: " + milvusConfig.getPort());
        System.out.println("Collection: " + milvusConfig.getCollectionName());
    }

    /**
     * 测试 EmbeddingStore 是否正确注入
     */
    @Test
    public void testEmbeddingStoreInjection() {
        assertNotNull(embeddingStore, "EmbeddingStore 应该被正确注入");
        System.out.println("EmbeddingStore (Milvus) 注入成功, dimension: " + milvusConfig.getDimension());
    }

    /**
     * 测试 Milvus 基本存取操作
     * 注意：此测试需要真实的 Milvus 环境运行，否则会报错
     */
    @Test
    public void testMilvusBasicOperation() throws InterruptedException {
        // 1. 准备测试数据
        String text = "这是一个关于人工智能的测试片段";
        TextSegment segment = TextSegment.from(text);

        // 2. 生成向量
        Embedding embedding = embeddingModel.embed(text).content();
        assertNotNull(embedding, "生成的向量不应为空");
        System.out.println("成功生成向量, 维度: " + embedding.dimension());

        // 3. 存储向量
        String id = embeddingStore.add(embedding, segment);
        assertNotNull(id, "存储后返回的 ID 不应为空");
        System.out.println("成功存储向量, ID: " + id);

        // 增加短暂等待，确保 Milvus 数据同步完成（特别是在非强一致性模式下）
        Thread.sleep(2000);

        // 4. 执行搜索
        // 生成查询向量
        Embedding queryEmbedding = embeddingModel.embed("人工智能测试").content();
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(1)
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        assertNotNull(searchResult, "搜索结果不应为空");

        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
        assertFalse(matches.isEmpty(), "应该能搜到刚才存储的片段");

        EmbeddingMatch<TextSegment> match = matches.get(0);
        assertEquals(text, match.embedded().text(), "搜索到的文本内容应匹配");
        assertTrue(match.score() > 0.9, "自搜索相似度应该很高");

        System.out.println("Milvus 基本存取操作测试成功！");
        System.out.println("匹配文本: " + match.embedded().text());
        System.out.println("匹配得分: " + match.score());
    }
}
