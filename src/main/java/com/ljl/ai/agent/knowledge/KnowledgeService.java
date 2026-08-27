package com.ljl.ai.agent.knowledge;

import com.ljl.ai.agent.config.KnowledgeConfig;
import com.ljl.ai.agent.model.entity.KnowledgeDocument;
import com.ljl.ai.agent.rag.MilvusHybridCollectionManager;
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
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

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

    @Autowired(required = false)
    private MilvusHybridCollectionManager hybridCollectionManager;
    
    /**
     * 同步飞书文档到知识库 - 使用乐观锁处理并发
     * BUG B003修复: 添加重试机制处理版本冲突，防止并发更新时丢失更新
     */
    public KnowledgeDocument syncFeishuDocument(String docToken, String documentType, List<String> tags) {
        log.info("开始同步飞书文档, docToken: {}", docToken);

        String content = feishuClient.getDocumentContent(docToken);
        if (content == null || content.isEmpty()) {
            log.error("获取飞书文档内容失败, docToken: {}", docToken);
            return null;
        }

        var docMeta = feishuClient.getDocumentMeta(docToken);
        String title = docMeta != null ? docMeta.getString("title") : "未命名文档";

        // 使用原子操作确保并发安全
        int maxRetries = 3;
        int retry = 0;

        while (retry < maxRetries) {
            List<String> newVectorIds = null;
            try {
                KnowledgeDocument existingDoc = findByFeishuDocToken(docToken);
                KnowledgeDocument document = existingDoc != null ? existingDoc : new KnowledgeDocument();
                List<String> previousVectorIds = existingDoc != null && existingDoc.getVectorIds() != null
                        ? new ArrayList<>(existingDoc.getVectorIds()) : List.of();

                // 设置文档属性
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
                    // @Version初始为null, MongoDB会设为0
                }
                document.setUpdateTime(LocalDateTime.now());

                // 处理文档向量（此处可能耗时）
                newVectorIds = processAndStoreDocument(document);
                document.setVectorIds(newVectorIds);
                document.setChunkCount(newVectorIds.size());

                // 保存 - 乐观锁会自动检查version
                // 如果版本不匹配会抛OptimisticLockingFailureException
                mongoTemplate.save(document);

                // MongoDB now points at the new vector set. Remove the old set so
                // updates do not leave stale chunks in Milvus.
                if (!previousVectorIds.isEmpty()) {
                    removeVectorsBestEffort(previousVectorIds, docToken);
                }

                log.info("飞书文档同步完成, docToken: {}, title: {}, chunks: {}",
                        docToken, title, newVectorIds.size());
                return document;

            } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                // The vector store is outside MongoDB's transaction. Remove the
                // vectors created by this failed attempt before retrying.
                if (newVectorIds != null) {
                    removeVectorsBestEffort(newVectorIds, docToken);
                }
                retry++;
                log.warn("版本冲突，正在重试 {}/{}, docToken: {}", retry, maxRetries, docToken);
                if (retry >= maxRetries) {
                    throw new RuntimeException("并发同步失败，超过最大重试次数: " + maxRetries, e);
                }
                // 指数退避
                try {
                    long sleepTime = 100L * retry;
                    Thread.sleep(sleepTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("同步中断", ie);
                }
            } catch (Exception e) {
                // Do not leave vectors behind when the MongoDB write fails.
                if (newVectorIds != null) {
                    removeVectorsBestEffort(newVectorIds, docToken);
                }
                log.error("飞书文档同步失败, docToken: {}", docToken, e);
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException(e);
            }
        }

        throw new RuntimeException("同步飞书文档失败，超过最大重试次数");
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
     * 处理文档并存储向量 - 带容错和补偿机制
     * BUG B002修复: 实现segment级别异常处理，确保部分失败时能清理已添加的向量
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

        // 处理向量 - 记录成功和失败的vectorIds
        List<String> successVectorIds = new ArrayList<>();
        List<Integer> failedSegmentIndices = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            try {
                Embedding embedding = embeddingModel.embed(segment).content();
                String vectorId = embeddingStore.add(embedding, segment);
                if (hybridCollectionManager != null) {
                    hybridCollectionManager.insert(document.getDocumentId() + ":" + i, document.getDocumentId(),
                            document.getTitle(), document.getDocumentType(), document.getSource(), segment.text(),
                            embedding.vector());
                }
                successVectorIds.add(vectorId);
            } catch (Exception e) {
                failedSegmentIndices.add(i);
                log.error("向量存储失败, segment索引: {}, error: {}", i, e.getMessage(), e);
            }
        }

        // 检查是否有失败
        if (!failedSegmentIndices.isEmpty()) {
            double successRate = (double) successVectorIds.size() / segments.size();
            log.warn("部分segment处理失败: {}/{}, 成功率: {}%",
                    failedSegmentIndices.size(), segments.size(), String.format("%.1f", successRate * 100));

            // A document must not be published with missing chunks. Roll back
            // all vectors created for this document and let the caller retry.
            log.error("存在失败的文档片段({}个)，执行补偿操作删除已添加的向量", failedSegmentIndices.size());
            for (String vectorId : successVectorIds) {
                try {
                    embeddingStore.remove(vectorId);
                    log.debug("补偿删除向量成功, vectorId: {}", vectorId);
                } catch (Exception e) {
                    log.error("补偿删除向量失败, vectorId: {}, error: {}", vectorId, e.getMessage());
                }
            }
            throw new RuntimeException(
                    String.format("向量存储失败，%d个片段未成功，已回滚", failedSegmentIndices.size()));
        }

        return successVectorIds;
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
     * 查询知识库管理列表，包含启用和已禁用文档。
     */
    public List<KnowledgeDocument> findAll() {
        Query query = new Query(Criteria.where("deleteStatus").ne("DELETED"))
                .with(Sort.by(Sort.Direction.DESC, "updateTime"));
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
     * 删除知识文档 - 两阶段删除确保数据一致性
     * BUG B001修复: 实现向量删除重试机制，防止向量删除失败导致MongoDB记录仍被删除的数据不一致
     * 流程: 标记删除中 -> 删除向量(重试3次) -> 删除MongoDB记录
     */
    public void deleteDocument(String documentId) {
        Query query = new Query(Criteria.where("documentId").is(documentId));
        KnowledgeDocument document = mongoTemplate.findOne(query, KnowledgeDocument.class);

        if (document == null) {
            log.warn("文档不存在, id: {}", documentId);
            return;
        }

        try {
            // 阶段1: 标记为删除中 - 防止重复删除
            Update markDelete = new Update()
                    .set("deleteStatus", "DELETING")
                    .set("deleteTimestamp", LocalDateTime.now());
            mongoTemplate.updateFirst(query, markDelete, KnowledgeDocument.class);
            log.debug("已标记文档为删除中, id: {}", documentId);

            // 阶段2: 删除向量（重试3次）
            if (document.getVectorIds() != null && !document.getVectorIds().isEmpty()) {
                deleteVectorsWithRetry(document.getVectorIds(), documentId);
            }

            // 阶段3: 删除MongoDB记录
            mongoTemplate.remove(query, KnowledgeDocument.class);
            log.info("知识文档已删除, id: {}, 向量数: {}", documentId,
                    document.getVectorIds() != null ? document.getVectorIds().size() : 0);

        } catch (Exception e) {
            log.error("删除文档失败，已标记为DELETING状态，需要手动处理, id: {}, error: {}",
                    documentId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 删除向量 - 带重试机制
     * @param vectorIds 要删除的向量ID列表
     * @param documentId 文档ID（用于日志）
     */
    private void deleteVectorsWithRetry(List<String> vectorIds, String documentId) {
        List<String> failedIds = new ArrayList<>();
        int maxRetries = 3;

        for (String vectorId : vectorIds) {
            int retries = 0;
            boolean success = false;

            while (retries < maxRetries && !success) {
                try {
                    embeddingStore.remove(vectorId);
                    success = true;
                    log.debug("向量删除成功, vectorId: {}", vectorId);
                } catch (Exception e) {
                    retries++;
                    log.warn("向量删除失败(第{}次尝试), vectorId: {}, error: {}",
                            retries, vectorId, e.getMessage());

                    if (retries < maxRetries) {
                        try {
                            // 指数退避: 100ms, 200ms, 400ms
                            long sleepTime = 100L * retries;
                            Thread.sleep(sleepTime);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            log.error("等待被中断, vectorId: {}", vectorId);
                            failedIds.add(vectorId);
                            break;
                        }
                    }
                }
            }

            if (!success) {
                failedIds.add(vectorId);
                log.error("向量删除最终失败(超过{}次重试), vectorId: {}", maxRetries, vectorId);
            }
        }

        if (!failedIds.isEmpty()) {
            log.error("向量删除部分失败，孤立向量ID列表({}个): {}", failedIds.size(), failedIds);
            throw new IllegalStateException("向量删除失败，文档保留为DELETING状态: " + failedIds.size());
        }
    }

    private void removeVectorsBestEffort(List<String> vectorIds, String docToken) {
        for (String vectorId : vectorIds) {
            try {
                embeddingStore.remove(vectorId);
            } catch (Exception e) {
                log.error("旧向量清理失败, docToken: {}, vectorId: {}", docToken, vectorId, e);
            }
        }
    }
    
    /**
     * 禁用知识文档
     */
    public void disableDocument(String documentId) {
        Query query = new Query(Criteria.where("documentId").is(documentId));
        KnowledgeDocument document = mongoTemplate.findOne(query, KnowledgeDocument.class);

        if (document == null) {
            throw new IllegalArgumentException("知识文档不存在: " + documentId);
        }
        if (Boolean.FALSE.equals(document.getEnabled())
                && (document.getVectorIds() == null || document.getVectorIds().isEmpty())) {
            return;
        }

        // Persist the retrieval barrier before touching Milvus. RetrievalService
        // checks this flag, so a failed vector deletion cannot expose the document.
        if (!Boolean.FALSE.equals(document.getEnabled())) {
            document.setEnabled(false);
            document.setUpdateTime(LocalDateTime.now());
            mongoTemplate.save(document);
        }
        if (document.getVectorIds() != null && !document.getVectorIds().isEmpty()) {
            deleteVectorsWithRetry(document.getVectorIds(), documentId);
        }
        if (hybridCollectionManager != null) hybridCollectionManager.deleteDocument(documentId);
        document.setVectorIds(List.of());
        document.setChunkCount(0);
        document.setUpdateTime(LocalDateTime.now());
        mongoTemplate.save(document);
        log.info("知识文档已禁用, id: {}", documentId);
    }

    /** 重新向量化并启用知识文档。 */
    public void enableDocument(String documentId) {
        Query query = new Query(Criteria.where("documentId").is(documentId));
        KnowledgeDocument document = mongoTemplate.findOne(query, KnowledgeDocument.class);
        if (document == null) throw new IllegalArgumentException("知识文档不存在: " + documentId);
        if (Boolean.TRUE.equals(document.getEnabled())) return;
        if (document.getRawContent() == null || document.getRawContent().isBlank()) {
            throw new IllegalStateException("知识文档没有可用于重建向量的原始内容: " + documentId);
        }

        if (document.getVectorIds() != null && !document.getVectorIds().isEmpty()) {
            deleteVectorsWithRetry(document.getVectorIds(), documentId);
            document.setVectorIds(List.of());
            document.setChunkCount(0);
        }

        List<String> vectorIds = processAndStoreDocument(document);
        try {
            document.setVectorIds(vectorIds);
            document.setChunkCount(vectorIds.size());
            document.setEnabled(true);
            document.setDeleteStatus(null);
            document.setUpdateTime(LocalDateTime.now());
            mongoTemplate.save(document);
            log.info("知识文档已重新启用, id: {}, chunks: {}", documentId, vectorIds.size());
        } catch (RuntimeException exception) {
            removeVectorsBestEffort(vectorIds, documentId);
            throw exception;
        }
    }
}
