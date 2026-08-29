package com.ljl.ai.config;

import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import com.ljl.ai.knowledge.MilvusHybridCollectionManager;
import com.ljl.ai.rag.MilvusHybridSearchClient;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import dev.langchain4j.data.segment.TextSegment;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量存储配置
 */
@Slf4j
@Configuration
public class EmbeddingStoreConfig {

    @Resource
    private  MilvusConfig milvusConfig;

    /**
     * 配置Milvus向量存储
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        log.info("初始化Milvus向量存储, host: {}, port: {}, collection: {}, dimension: {}",
                milvusConfig.getHost(), milvusConfig.getPort(), milvusConfig.getCollectionName(), milvusConfig.getDimension());
        
        return MilvusEmbeddingStore.builder()
                .host(milvusConfig.getHost())
                .port(milvusConfig.getPort())
                .collectionName(milvusConfig.getCollectionName())
                .dimension(milvusConfig.getDimension())
                .build();
    }

    @Bean(destroyMethod = "close")
    public MilvusClientV2 milvusClientV2() {
        return new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + milvusConfig.getHost() + ":" + milvusConfig.getPort())
                .build());
    }

    @Bean
    public MilvusHybridCollectionManager milvusHybridCollectionManager(MilvusClientV2 milvusClientV2) {
        return new MilvusHybridCollectionManager(milvusClientV2, milvusConfig.getHybridCollectionName(),
                milvusConfig.getDimension());
    }

    @Bean
    public MilvusHybridSearchClient milvusHybridSearchClient(MilvusClientV2 milvusClientV2) {
        return new MilvusHybridSearchClient(milvusClientV2, milvusConfig.getHybridCollectionName(), 60);
    }


}
