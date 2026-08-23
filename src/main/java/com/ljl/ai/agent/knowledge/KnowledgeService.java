package com.ljl.ai.agent.knowledge;

import com.ljl.ai.agent.config.KnowledgeConfig;
import com.ljl.ai.agent.model.entity.KnowledgeDocument;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库服务 - 负责文档处理、向量化和存储
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {
    
    private final FeishuClient feishuClient;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final MongoTemplate mongoTemplate;
    private final KnowledgeConfig knowledgeConfig;
    
    /**
     * 同步飞书文档到知识库
     */
    public KnowledgeDocument syncFeishuDocument(String docToken, String documentType, List<String> tags) {
        log.info("开始同步飞书文档, docToken: {}", docToken);
        
        // 获取文档内容
        String content = feishuClient.getDocumentContent(docToken);
        if (content == null || content.isEmpty()) {
            log.error("获取飞书文档内容失败, docToken: {}", docToken);
            return null;
        }
        
        // 获取文档元信息
        var docMeta = feishuClient.getDocumentMeta(docToken);
        String title = docMeta != null ? docMeta.getString("title") : "未命名文档";
        
        // 检查是否已存在
        KnowledgeDocument existingDoc = findByFeishuDocToken(docToken);
        
        // 创建或更新文档记录
        KnowledgeDocument document = existingDoc != null ? existingDoc : new KnowledgeDocument();
        document.setFeishuDocToken(docToken);
        document.setTitle(title);
        document.setDocumentType(documentType);
        document.setRawContent(content);
        document.setSource("FEISHU");
        document.setUrl("https://xxx.feishu.cn/docx/" + docToken);
        document.setTags(tags);
        document.setSyncTime(LocalDateTime.now());
        document.setEnabled(true);
        
        if (existingDoc == null) {
            document.setDocumentId(UUID.randomUUID().toString());
            document.setCreateTime(LocalDateTime.now());
            document.setVersion(1);
        } else {
            document.setVersion(existingDoc.getVersion() + 1);
        }
        document.setUpdateTime(LocalDateTime.now());
        
        // 处理文档并存储向量
        List<String> vectorIds = processAndStoreDocument(document);
        document.setVectorIds(vectorIds);
        document.setChunkCount(vectorIds.size());
        
        // 保存到MongoDB
        mongoTemplate.save(document);
        
        log.info("飞书文档同步完成, docToken: {}, title: {}, chunks: {}", 
                docToken, title, vectorIds.size());
        
        return document;
    }
    
    /**
     * 添加自定义知识文档
     */
    public KnowledgeDocument addKnowledgeDocument(String title, String content, String documentType, 
                                                   List<String> tags, Map<String, String> metadata) {
        log.info("添加知识文档, title: {}", title);
        
        KnowledgeDocument document = KnowledgeDocument.builder()
                .documentId(UUID.randomUUID().toString())
                .title(title)
                .rawContent(content)
                .documentType(documentType)
                .source("MANUAL")
                .tags(tags)
                .metadata(metadata)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .version(1)
                .enabled(true)
                .build();
        
        // 处理文档并存储向量
        List<String> vectorIds = processAndStoreDocument(document);
        document.setVectorIds(vectorIds);
        document.setChunkCount(vectorIds.size());
        
        // 保存到MongoDB
        mongoTemplate.save(document);
        
        log.info("知识文档添加完成, id: {}, title: {}, chunks: {}", 
                document.getDocumentId(), title, vectorIds.size());
        
        return document;
    }
    
    /**
     * 处理文档并存储向量
     */
    private List<String> processAndStoreDocument(KnowledgeDocument document) {
        // 创建LangChain4j文档
        Document doc = Document.from(document.getRawContent(), Metadata.from(Map.of(
                "documentId", document.getDocumentId(),
                "title", document.getTitle(),
                "documentType", document.getDocumentType() != null ? document.getDocumentType() : "",
                "source", document.getSource() != null ? document.getSource() : ""
        )));
        
        // 文档分块
        DocumentSplitter splitter = DocumentSplitters.recursive(
                knowledgeConfig.getChunk().getSize(),
                knowledgeConfig.getChunk().getOverlap()
        );
        List<TextSegment> segments = splitter.split(doc);
        
        log.info("文档分块完成, 共 {} 个片段", segments.size());
        
        // 生成向量并存储
        List<String> vectorIds = new ArrayList<>();
        for (TextSegment segment : segments) {
            Embedding embedding = embeddingModel.embed(segment).content();
            String vectorId = embeddingStore.add(embedding, segment);
            vectorIds.add(vectorId);
        }
        
        return vectorIds;
    }
    
    /**
     * 根据飞书文档Token查找
     */
    public KnowledgeDocument findByFeishuDocToken(String docToken) {
        Query query = new Query(Criteria.where("feishuDocToken").is(docToken));
        return mongoTemplate.findOne(query, KnowledgeDocument.class);
    }
    
    /**
     * 查询所有启用的知识文档
     */
    public List<KnowledgeDocument> findAllEnabled() {
        Query query = new Query(Criteria.where("enabled").is(true));
        return mongoTemplate.find(query, KnowledgeDocument.class);
    }
    
    /**
     * 根据类型查询文档
     */
    public List<KnowledgeDocument> findByType(String documentType) {
        Query query = new Query(Criteria.where("documentType").is(documentType).and("enabled").is(true));
        return mongoTemplate.find(query, KnowledgeDocument.class);
    }
    
    /**
     * 删除知识文档
     */
    public void deleteDocument(String documentId) {
        Query query = new Query(Criteria.where("documentId").is(documentId));
        KnowledgeDocument document = mongoTemplate.findOne(query, KnowledgeDocument.class);
        
        if (document != null) {
            // 删除向量存储中的数据
            if (document.getVectorIds() != null) {
                document.getVectorIds().forEach(embeddingStore::remove);
            }
            // 删除MongoDB记录
            mongoTemplate.remove(query, KnowledgeDocument.class);
            log.info("知识文档已删除, id: {}", documentId);
        }
    }
    
    /**
     * 禁用知识文档
     */
    public void disableDocument(String documentId) {
        Query query = new Query(Criteria.where("documentId").is(documentId));
        KnowledgeDocument document = mongoTemplate.findOne(query, KnowledgeDocument.class);
        
        if (document != null) {
            document.setEnabled(false);
            document.setUpdateTime(LocalDateTime.now());
            mongoTemplate.save(document);
            log.info("知识文档已禁用, id: {}", documentId);
        }
    }
}
