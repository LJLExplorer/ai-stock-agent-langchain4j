package com.ljl.ai.knowledge;

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
                + "（一）白酒业务\n白酒正文。\n1. 关键指标\n指标正文。\n1.1 收入表现\n收入正文。";

        List<HierarchicalDocumentChunker.ParentDraft> sections =
                chunker.parseSections("贵州茅台 2025 年报", content, List.of("年报"), Map.of("source", "test"));

        assertEquals(7, sections.size());
        assertEquals(List.of("年报"), sections.get(0).getHeadingPath());
        assertEquals(List.of("年报", "管理层讨论"), sections.get(1).getHeadingPath());
        assertEquals(List.of("第一章 经营情况"), sections.get(2).getHeadingPath());
        assertEquals(List.of("第一章 经营情况", "一、主营业务"), sections.get(3).getHeadingPath());
        assertEquals(List.of("第一章 经营情况", "一、主营业务", "（一）白酒业务"), sections.get(4).getHeadingPath());
        assertEquals(List.of("第一章 经营情况", "一、主营业务", "（一）白酒业务", "1. 关键指标"), sections.get(5).getHeadingPath());
        assertEquals(List.of("第一章 经营情况", "一、主营业务", "（一）白酒业务", "1. 关键指标", "1.1 收入表现"), sections.get(6).getHeadingPath());
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
}
