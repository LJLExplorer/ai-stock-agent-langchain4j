package com.ljl.ai.knowledge;

import com.ljl.ai.model.entity.KnowledgeDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchicalDocumentChunkerTest {

    private final HierarchicalDocumentChunker chunker = new HierarchicalDocumentChunker();

    @Test
    void parsesHeadingHierarchy() {
        String content = "# 年报\n概览正文。\n## 管理层讨论\n讨论正文。\n"
                + "第一章 经营情况\n章节正文。\n一、主营业务\n业务正文。\n"
                + "（一）白酒业务\n白酒正文。\n1. 一级指标\n指标正文。\n1.1 收入表现\n收入正文。";

        List<HierarchicalDocumentChunker.ParentDraft> sections =
                chunker.parseSections("贵州茅台 2025 年报", content, List.of("年报"), Map.of("source", "test"));

        assertEquals(7, sections.size());
        assertEquals(List.of("年报"), sections.get(0).getHeadingPath());
        assertEquals(List.of("年报", "管理层讨论"), sections.get(1).getHeadingPath());
        assertEquals(List.of("第一章 经营情况"), sections.get(2).getHeadingPath());
        assertEquals(List.of("第一章 经营情况", "一、主营业务"), sections.get(3).getHeadingPath());
        assertEquals(List.of("第一章 经营情况", "一、主营业务", "（一）白酒业务"), sections.get(4).getHeadingPath());
        assertEquals(List.of("1. 一级指标"), sections.get(5).getHeadingPath());
        assertEquals(List.of("1. 一级指标", "1.1 收入表现"), sections.get(6).getHeadingPath());
        assertEquals(List.of("年报"), sections.get(0).getTags());
        assertEquals("test", sections.get(0).getMetadata().get("source"));
        assertEquals(0, sections.get(0).getSectionIndex());
        assertEquals(6, sections.get(6).getSectionIndex());
    }

    @Test
    void doesNotTreatOrdinaryShortLinesOrFinancialMetricsAsHeadings() {
        String content = "营业收入\n毛利率 35%\n这是对经营表现的说明。";

        List<HierarchicalDocumentChunker.ParentDraft> sections =
                chunker.parseSections("财务分析", content, List.of(), Map.of());

        assertEquals(1, sections.size());
        assertEquals(List.of("财务分析"), sections.getFirst().getHeadingPath());
        assertTrue(sections.getFirst().getContent().contains("营业收入"));
        assertTrue(sections.getFirst().getContent().contains("毛利率 35%"));
        assertFalse(sections.getFirst().getContent().isBlank());
    }

    @Test
    void usesDocumentTitleAsRootWhenDocumentHasNoHeading() {
        List<HierarchicalDocumentChunker.ParentDraft> sections = chunker.parseSections(
                "无标题研究笔记", "第一段。\n\n第二段。", List.of("笔记"), Map.of("year", "2025"));

        assertEquals(1, sections.size());
        assertEquals(List.of("无标题研究笔记"), sections.getFirst().getHeadingPath());
        assertEquals("第一段。\n\n第二段。", sections.getFirst().getContent());
        assertEquals("2025", sections.getFirst().getMetadata().get("year"));
    }

    @Test
    void preservesLeadingContentAsDocumentTitleRootParent() {
        List<HierarchicalDocumentChunker.ParentDraft> sections = chunker.parseSections(
                "研究报告", "前言内容。\n# 第一章\n章节内容。", List.of(), Map.of());

        assertEquals(2, sections.size());
        assertEquals(List.of("研究报告"), sections.get(0).getHeadingPath());
        assertEquals("前言内容。", sections.get(0).getContent());
        assertEquals(List.of("第一章"), sections.get(1).getHeadingPath());
        assertEquals("章节内容。", sections.get(1).getContent());
    }

    @Test
    void usesDecimalSegmentCountAsHeadingLevel() {
        List<HierarchicalDocumentChunker.ParentDraft> sections = chunker.parseSections(
                "研究报告", "1.1 二级标题\n二级正文。\n1.1.1 三级标题\n三级正文。\n1.2 同级标题\n同级正文。", List.of(), Map.of());

        assertEquals(3, sections.size());
        assertEquals(List.of("1.1 二级标题"), sections.get(0).getHeadingPath());
        assertEquals(List.of("1.1 二级标题", "1.1.1 三级标题"), sections.get(1).getHeadingPath());
        assertEquals(List.of("1.2 同级标题"), sections.get(2).getHeadingPath());
    }

    @Test
    void splitsChildrenByParagraphSentenceAndCharacter() {
        String paragraph = "段落标记" + "甲".repeat(330) + "。";
        String hardLimitSentence = "超长句标记" + "乙".repeat(1_700);
        KnowledgeDocument document = KnowledgeDocument.builder()
                .documentId("document-1")
                .title("测试文档")
                .rawContent(String.join("\n\n", paragraph, paragraph, paragraph, hardLimitSentence))
                .build();

        HierarchicalDocumentChunker.ChunkedDocument chunked = chunker.chunk(document, "version-1");

        assertTrue(chunked.getChildren().size() >= 3);
        for (int index = 0; index < chunked.getChildren().size(); index++) {
            HierarchicalDocumentChunker.ChildDraft child = chunked.getChildren().get(index);
            assertEquals(index, child.getChunkIndex());
            assertTrue(child.getContent().length() <= 800);
            assertTrue(child.getEmbeddingText().contains("测试文档"));
        }
        assertTrue(chunked.getChildren().stream().anyMatch(child -> child.getContent().contains("超长句标记")));
    }

    @Test
    void keepsOverlapWithinParent() {
        String firstParent = "第一父段标记" + "甲".repeat(2_400) + "。";
        String secondParent = "第二父段标记" + "乙".repeat(2_400) + "。";
        KnowledgeDocument document = KnowledgeDocument.builder()
                .documentId("document-2")
                .title("测试文档")
                .rawContent("# 第一父段\n" + firstParent + "\n# 第二父段\n" + secondParent)
                .build();

        HierarchicalDocumentChunker.ChunkedDocument chunked = chunker.chunk(document, "version-2");

        List<HierarchicalDocumentChunker.ChildDraft> firstChildren = chunked.getChildren().stream()
                .filter(child -> child.getParentSectionIndex() == 0).toList();
        List<HierarchicalDocumentChunker.ChildDraft> secondChildren = chunked.getChildren().stream()
                .filter(child -> child.getParentSectionIndex() == 1).toList();
        assertTrue(firstChildren.size() > 1);
        assertTrue(secondChildren.size() > 1);
        assertTrue(firstChildren.stream().allMatch(child -> child.getContent().contains("甲")));
        assertTrue(secondChildren.stream().allMatch(child -> child.getContent().contains("乙")));
        for (int index = 1; index < firstChildren.size(); index++) {
            HierarchicalDocumentChunker.ChildDraft child = firstChildren.get(index);
            assertEquals(index, child.getChunkIndex());
            int overlapLength = child.getStartOffset() - child.getOverlapStartOffset();
            assertTrue(overlapLength >= 80 && overlapLength <= 120);
        }
    }

    @Test
    void allowsShortTailWhenMergeWouldOverflow() {
        String content = "尾块前段标记" + "甲".repeat(760) + "。\n\n尾块标记" + "乙".repeat(180) + "。";
        KnowledgeDocument document = KnowledgeDocument.builder()
                .documentId("document-3")
                .title("测试文档")
                .rawContent(content)
                .build();

        HierarchicalDocumentChunker.ChunkedDocument chunked = chunker.chunk(document, "version-3");

        assertEquals(2, chunked.getChildren().size());
        HierarchicalDocumentChunker.ChildDraft tail = chunked.getChildren().getLast();
        assertTrue(tail.getContent().contains("尾块标记"));
        assertTrue(tail.getContent().length() < 600);
        assertTrue(tail.getContent().length() <= 800);
    }
}
