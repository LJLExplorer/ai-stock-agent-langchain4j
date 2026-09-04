package com.ljl.ai.model.entity;

import com.ljl.ai.config.KnowledgeConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeSectionTest {

    @Test
    void shouldExposeHierarchicalChunkingDefaults() {
        KnowledgeConfig.ChunkConfig chunk = new KnowledgeConfig().getChunk();

        assertEquals(600, chunk.getMinSize());
        assertEquals(700, chunk.getTargetSize());
        assertEquals(800, chunk.getMaxSize());
        assertEquals(80, chunk.getMinOverlap());
        assertEquals(120, chunk.getMaxOverlap());
        assertEquals(1200, chunk.getShortParentThreshold());
        assertEquals(400, chunk.getSummaryMinSize());
        assertEquals(600, chunk.getSummaryMaxSize());
        assertEquals("hierarchical-v1", chunk.getStrategyVersion());
    }

    @Test
    void shouldRetainVersionedParentSectionAndChildSpans() {
        KnowledgeSection.ChunkSpan span = KnowledgeSection.ChunkSpan.builder()
                .chunkId("child-1")
                .chunkIndex(0)
                .startOffset(0)
                .endOffset(700)
                .overlapStartOffset(0)
                .build();
        KnowledgeSection section = KnowledgeSection.builder()
                .sectionRecordId("ingestion-1:doc-1:0")
                .sectionId("doc-1:0")
                .documentId("doc-1")
                .ingestionVersion("ingestion-1")
                .headingPath(List.of("2025 年报", "管理层讨论"))
                .content("章节正文")
                .contentLength(4)
                .summary("章节摘要")
                .stockCode("600519")
                .year("2025")
                .tags(List.of("白酒", "年报"))
                .sectionIndex(0)
                .childCount(1)
                .chunkSpans(List.of(span))
                .build();

        assertEquals("doc-1:0", section.getSectionId());
        assertEquals("ingestion-1", section.getIngestionVersion());
        assertEquals(List.of("2025 年报", "管理层讨论"), section.getHeadingPath());
        assertEquals("章节正文", section.getContent());
        assertEquals("章节摘要", section.getSummary());
        assertEquals("600519", section.getStockCode());
        assertEquals("2025", section.getYear());
        assertEquals(List.of("白酒", "年报"), section.getTags());
        assertEquals(List.of(span), section.getChunkSpans());
        assertEquals(700, section.getChunkSpans().getFirst().getEndOffset());
    }
}
