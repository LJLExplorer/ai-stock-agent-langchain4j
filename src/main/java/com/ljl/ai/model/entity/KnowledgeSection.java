package com.ljl.ai.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

/**
 * 可版本化持久化的知识文档 Parent Section。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "knowledge_sections")
public class KnowledgeSection {

    @Id
    private String sectionRecordId;
    private String sectionId;
    private String documentId;
    private String ingestionVersion;
    private List<String> headingPath;
    private String content;
    private int contentLength;
    private String summary;
    private String stockCode;
    private String year;
    private List<String> tags;
    private int sectionIndex;
    private int childCount;
    private List<ChunkSpan> chunkSpans;

    /**
     * Child 在 Parent 原文中的 Java String 字符索引区间。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkSpan {
        private String chunkId;
        private int chunkIndex;
        private int startOffset;
        private int endOffset;
        private int overlapStartOffset;
    }
}
