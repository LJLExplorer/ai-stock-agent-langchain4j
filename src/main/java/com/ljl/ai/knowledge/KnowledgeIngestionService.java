package com.ljl.ai.knowledge;

import com.ljl.ai.knowledge.HierarchicalDocumentChunker.ChildDraft;
import com.ljl.ai.knowledge.HierarchicalDocumentChunker.ChunkedDocument;
import com.ljl.ai.knowledge.HierarchicalDocumentChunker.ParentDraft;
import com.ljl.ai.knowledge.MilvusHybridCollectionManager.HybridChunkRow;
import com.ljl.ai.model.entity.KnowledgeDocument;
import com.ljl.ai.model.entity.KnowledgeSection;
import com.ljl.ai.model.entity.KnowledgeSection.ChunkSpan;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 将一个确定的分层切分结果原子地写入 Parent、语义向量和 Hybrid 向量索引。
 * MongoDB 文档的活动版本由调用方在所有这些写入成功后发布。
 */
@Slf4j
@Service
public class KnowledgeIngestionService {

    private final HierarchicalDocumentChunker chunker;
    private final KnowledgeSectionStore sectionStore;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final MilvusHybridCollectionManager hybridCollectionManager;

    public KnowledgeIngestionService(HierarchicalDocumentChunker chunker, KnowledgeSectionStore sectionStore,
                                     EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore,
                                     MilvusHybridCollectionManager hybridCollectionManager) {
        this.chunker = chunker;
        this.sectionStore = sectionStore;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.hybridCollectionManager = hybridCollectionManager;
    }

    public IngestionResult ingest(KnowledgeDocument document) {
        if (document == null || document.getDocumentId() == null || document.getDocumentId().isBlank()) {
            throw new IllegalArgumentException("知识文档及 documentId 不能为空");
        }
        String ingestionVersion = UUID.randomUUID().toString();
        ChunkedDocument chunked = chunker.chunk(document, ingestionVersion);
        List<String> vectorIds = new ArrayList<>();
        IngestionResult result = new IngestionResult(document.getDocumentId(), ingestionVersion, vectorIds,
                chunked.getChildren().size());
        try {
            sectionStore.saveAll(toSections(document, ingestionVersion, chunked));
            for (ChildDraft child : chunked.getChildren()) {
                Embedding embedding = embeddingModel.embed(child.getEmbeddingText()).content();
                int parentChildCount = childCount(chunked, child);
                TextSegment segment = TextSegment.from(child.getContent(), Metadata.from(metadata(document, child, ingestionVersion,
                        parentChildCount)));
                String vectorId = embeddingStore.add(embedding, segment);
                vectorIds.add(vectorId);
                hybridCollectionManager.insert(new HybridChunkRow(child.getChunkId(), document.getDocumentId(), ingestionVersion,
                        child.getParentSectionId(), child.getHeadingPath(), child.getChunkIndex(), childCount(chunked, child),
                        child.getStockCode(), child.getYear(), child.getTags(), document.getTitle(), document.getDocumentType(),
                        document.getSource(), child.getContent(), embedding.vector()));
            }
            return result;
        } catch (RuntimeException exception) {
            rollback(result);
            throw exception;
        }
    }

    /** 补偿仅限本次 version，绝不影响已发布版本。 */
    public void rollback(IngestionResult result) {
        if (result == null) return;
        for (String vectorId : result.vectorIds()) {
            try {
                embeddingStore.remove(vectorId);
            } catch (RuntimeException cleanupFailure) {
                log.error("回滚语义 Child 失败, vectorId: {}", vectorId, cleanupFailure);
            }
        }
        if (result.documentId() == null || result.documentId().isBlank()) return;
        try {
            hybridCollectionManager.deleteDocumentVersion(result.documentId(), result.ingestionVersion());
        } catch (RuntimeException cleanupFailure) {
            log.error("回滚 Hybrid Child 失败, documentId: {}, version: {}", result.documentId(),
                    result.ingestionVersion(), cleanupFailure);
        }
        try {
            sectionStore.deleteVersion(result.documentId(), result.ingestionVersion());
        } catch (RuntimeException cleanupFailure) {
            log.error("回滚 Parent Section 失败, documentId: {}, version: {}", result.documentId(),
                    result.ingestionVersion(), cleanupFailure);
        }
    }

    private List<KnowledgeSection> toSections(KnowledgeDocument document, String ingestionVersion,
                                               ChunkedDocument chunked) {
        List<KnowledgeSection> sections = new ArrayList<>();
        for (ParentDraft parent : chunked.getParents()) {
            String sectionId = document.getDocumentId() + ":" + parent.getSectionIndex();
            List<ChildDraft> children = chunked.getChildren().stream()
                    .filter(child -> sectionId.equals(child.getParentSectionId())).toList();
            List<ChunkSpan> spans = children.stream().map(child -> ChunkSpan.builder()
                    .chunkId(child.getChunkId()).chunkIndex(child.getChunkIndex())
                    .startOffset(child.getStartOffset()).endOffset(child.getEndOffset())
                    .overlapStartOffset(child.getOverlapStartOffset()).build()).toList();
            sections.add(KnowledgeSection.builder().sectionId(sectionId).documentId(document.getDocumentId())
                    .ingestionVersion(ingestionVersion).headingPath(parent.getHeadingPath()).content(parent.getContent())
                    .contentLength(parent.getContent().length()).summary(parent.getSummary()).stockCode(parent.getStockCode())
                    .year(parent.getYear()).tags(parent.getTags()).sectionIndex(parent.getSectionIndex())
                    .childCount(children.size()).chunkSpans(spans).build());
        }
        return sections;
    }

    private Map<String, String> metadata(KnowledgeDocument document, ChildDraft child, String ingestionVersion,
                                         int totalChildCount) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("documentId", document.getDocumentId());
        metadata.put("chunkId", child.getChunkId());
        metadata.put("ingestionVersion", ingestionVersion);
        metadata.put("parentSectionId", child.getParentSectionId());
        metadata.put("headingPath", String.join(" > ", child.getHeadingPath()));
        metadata.put("chunkIndex", String.valueOf(child.getChunkIndex()));
        metadata.put("chunkCount", String.valueOf(totalChildCount));
        metadata.put("stockCode", value(child.getStockCode()));
        metadata.put("year", value(child.getYear()));
        metadata.put("tags", String.join(",", child.getTags()));
        metadata.put("title", value(document.getTitle()));
        metadata.put("documentType", value(document.getDocumentType()));
        metadata.put("source", value(document.getSource()));
        return metadata;
    }

    private int childCount(ChunkedDocument chunked, ChildDraft child) {
        return (int) chunked.getChildren().stream()
                .filter(candidate -> candidate.getParentSectionId().equals(child.getParentSectionId())).count();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    public record IngestionResult(String documentId, String ingestionVersion, List<String> vectorIds, int chunkCount) {
        public IngestionResult {
            vectorIds = vectorIds == null ? List.of() : vectorIds;
        }

        public IngestionResult(String ingestionVersion, List<String> vectorIds, int chunkCount) {
            this(null, ingestionVersion, vectorIds, chunkCount);
        }
    }
}
