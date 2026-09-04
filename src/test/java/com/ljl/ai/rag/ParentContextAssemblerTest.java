package com.ljl.ai.rag;

import com.ljl.ai.model.entity.KnowledgeSection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParentContextAssemblerTest {

    private final ParentContextAssembler assembler = new ParentContextAssembler();

    @Test
    void returnsShortParentOnlyOnceEvenWhenSeveralChildrenMatch() {
        KnowledgeSection section = section("short", "简短 Parent 全文", 0, 8);

        List<RetrievalResult> results = assembler.assemble(List.of(
                hit("short-0", "short", 0, 0.3, 0.1, 0),
                hit("short-1", "short", 1, 0.8, 0.4, 1)),
                Map.of(key(section), section), 5);

        assertEquals(1, results.size());
        assertTrue(results.getFirst().getContent().contains(section.getContent()));
        assertEquals(List.of("short-0", "short-1"), results.getFirst().getMatchedChunkIds());
        assertEquals(0, results.getFirst().getWindowStartIndex());
        assertEquals(1, results.getFirst().getWindowEndIndex());
        assertEquals("short", results.getFirst().getParentSectionId());
        assertEquals(List.of("年报", "经营情况"), results.getFirst().getHeadingPath());
    }

    @Test
    void expandsEdgeNeighboursMergesOverlappingWindowsAndUsesCharacterIntervalsOnce() {
        KnowledgeSection section = longSection("long", 6);

        List<RetrievalResult> results = assembler.assemble(List.of(
                hit("long-2", "long", 2, 0.7, 0.3, 0),
                hit("long-3", "long", 3, 0.6, 0.8, 1)),
                Map.of(key(section), section), 5);

        assertEquals(1, results.size());
        RetrievalResult result = results.getFirst();
        assertEquals(1, result.getWindowStartIndex());
        assertEquals(4, result.getWindowEndIndex());
        assertEquals(List.of("long-2", "long-3"), result.getMatchedChunkIds());
        String expectedWindowText = section.getContent().substring(8, 42);
        assertTrue(result.getContent().contains(expectedWindowText));
        assertEquals(result.getContent().indexOf(expectedWindowText), result.getContent().lastIndexOf(expectedWindowText));
        assertEquals("父章节摘要", result.getParentSummary());

        List<RetrievalResult> edge = assembler.assemble(List.of(hit("long-0", "long", 0, 0.7, 0.3, 0)),
                Map.of(key(section), section), 5);
        assertEquals(0, edge.getFirst().getWindowStartIndex());
        assertEquals(1, edge.getFirst().getWindowEndIndex());
        assertTrue(edge.getFirst().getContent().contains(section.getContent().substring(0, 18)));
    }

    @Test
    void retainsDisjointWindowsAndSortsThenLimitsByWindowScores() {
        KnowledgeSection high = longSection("high", 7);
        KnowledgeSection tied = longSection("tied", 7);

        List<ParentContextAssembler.ChildHit> hits = List.of(
                hit("high-1", "high", 1, 0.8, 0.2, 4),
                hit("high-6", "high", 6, 0.9, 0.2, 5),
                hit("tied-3", "tied", 3, 0.9, 0.7, 2),
                hit("tied-4", "tied", 4, 0.9, 0.6, 3));
        List<RetrievalResult> results = assembler.assemble(hits,
                Map.of(key(high), high, key(tied), tied), 3);

        assertEquals(3, results.size());
        assertEquals("tied", results.get(0).getParentSectionId()); // same RRF, more hits wins
        assertEquals("high", results.get(1).getParentSectionId());
        assertEquals(5, results.get(1).getWindowStartIndex());
        assertEquals(0, results.get(2).getWindowStartIndex());
        assertEquals(2, assembler.assemble(hits, Map.of(key(high), high, key(tied), tied), 2).size());
    }

    private ParentContextAssembler.ChildHit hit(String chunkId, String parentId, int chunkIndex,
                                                double rrf, double semantic, int originalOrder) {
        return new ParentContextAssembler.ChildHit(chunkId, parentId, "v1", chunkIndex,
                RetrievalResult.builder().documentId("doc-1").title("文档标题").source("test")
                        .rrfScore(rrf).semanticScore(semantic).similarity(semantic).build(), originalOrder);
    }

    private ParentContextAssembler.SectionVersionKey key(KnowledgeSection section) {
        return new ParentContextAssembler.SectionVersionKey(section.getSectionId(), section.getIngestionVersion());
    }

    private KnowledgeSection section(String id, String content, int start, int end) {
        return KnowledgeSection.builder().sectionId(id).ingestionVersion("v1").documentId("doc-1")
                .headingPath(List.of("年报", "经营情况")).content(content).contentLength(content.length())
                .chunkSpans(List.of(span(id + "-0", 0, start, end, start), span(id + "-1", 1, start, end, start)))
                .build();
    }

    private KnowledgeSection longSection(String id, int chunkCount) {
        String content = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        List<KnowledgeSection.ChunkSpan> spans = new ArrayList<>();
        for (int index = 0; index < chunkCount; index++) {
            int start = index * 8;
            spans.add(span(id + "-" + index, index, start, start + 10, index == 0 ? 0 : start));
        }
        return KnowledgeSection.builder().sectionId(id).ingestionVersion("v1").documentId("doc-1")
                .headingPath(List.of("年报", "经营情况")).content(content).contentLength(1_201).summary("父章节摘要")
                .chunkSpans(spans).childCount(chunkCount).build();
    }

    private KnowledgeSection.ChunkSpan span(String id, int index, int start, int end, int overlapStart) {
        return KnowledgeSection.ChunkSpan.builder().chunkId(id).chunkIndex(index).startOffset(start)
                .endOffset(end).overlapStartOffset(overlapStart).build();
    }
}
