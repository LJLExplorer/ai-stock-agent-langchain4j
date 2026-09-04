package com.ljl.ai.knowledge;

import com.ljl.ai.model.entity.KnowledgeDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void prioritizesParagraphsBeforeSentencesUnlessParagraphIsOverlong() {
        String firstParagraph = "第一段落标记" + "甲".repeat(693) + "。";
        String secondParagraph = "第二段落标记" + "乙".repeat(900) + "。";
        KnowledgeDocument paragraphDocument = KnowledgeDocument.builder()
                .documentId("paragraph-priority")
                .title("段落优先")
                .rawContent(firstParagraph + "\n\n" + secondParagraph)
                .build();

        HierarchicalDocumentChunker.ChunkedDocument paragraphChunked = chunker.chunk(paragraphDocument, "version-4");

        assertEquals(firstParagraph.length() + 2, paragraphChunked.getChildren().getFirst().getEndOffset());

        String longParagraph = "超长单段标记" + "丙".repeat(690) + "。" + "丁".repeat(690) + "。";
        KnowledgeDocument sentenceDocument = KnowledgeDocument.builder()
                .documentId("sentence-fallback")
                .title("句子兜底")
                .rawContent(longParagraph)
                .build();

        HierarchicalDocumentChunker.ChunkedDocument sentenceChunked = chunker.chunk(sentenceDocument, "version-5");

        assertEquals(longParagraph.indexOf('。') + 1, sentenceChunked.getChildren().getFirst().getEndOffset());
    }

    @Test
    void inheritsFinancialMetadata() {
        KnowledgeDocument explicitDocument = KnowledgeDocument.builder()
                .documentId("financial-explicit")
                .title("贵州茅台 600519 2024 年报")
                .rawContent("# 2023 年经营回顾\n正文提及 000858 与 2021 年。")
                .tags(List.of("年报", "白酒"))
                .metadata(Map.of("stockCode", "000858", "year", "2022"))
                .build();

        HierarchicalDocumentChunker.ChunkedDocument explicit = chunker.chunk(explicitDocument, "version-financial");
        HierarchicalDocumentChunker.ParentDraft explicitParent = explicit.getParents().getFirst();
        HierarchicalDocumentChunker.ChildDraft explicitChild = explicit.getChildren().getFirst();
        assertEquals("000858", explicitParent.getStockCode());
        assertEquals("2022", explicitParent.getYear());
        assertEquals(List.of("年报", "白酒"), explicitParent.getTags());
        assertEquals("000858", explicitChild.getStockCode());
        assertEquals("2022", explicitChild.getYear());
        assertEquals(List.of("年报", "白酒"), explicitChild.getTags());

        KnowledgeDocument titleDocument = KnowledgeDocument.builder()
                .documentId("financial-title")
                .title("贵州茅台 600519 2024 年报")
                .rawContent("普通正文。")
                .build();
        HierarchicalDocumentChunker.ParentDraft titleParent = chunker.chunk(titleDocument, "version-title")
                .getParents().getFirst();
        assertEquals("600519", titleParent.getStockCode());
        assertEquals("2024", titleParent.getYear());

        KnowledgeDocument headingDocument = KnowledgeDocument.builder()
                .documentId("financial-heading")
                .title("行业研究")
                .rawContent("# 五粮液 000858 2023 年报\n普通正文。")
                .build();
        HierarchicalDocumentChunker.ParentDraft headingParent = chunker.chunk(headingDocument, "version-heading")
                .getParents().getFirst();
        assertEquals("000858", headingParent.getStockCode());
        assertEquals("2023", headingParent.getYear());

        KnowledgeDocument bodyDocument = KnowledgeDocument.builder()
                .documentId("financial-body")
                .title("行业研究")
                .rawContent("正文提及股票代码 300750，分析 2025 年表现。")
                .build();
        HierarchicalDocumentChunker.ParentDraft bodyParent = chunker.chunk(bodyDocument, "version-body")
                .getParents().getFirst();
        assertEquals("300750", bodyParent.getStockCode());
        assertEquals("2025", bodyParent.getYear());

        KnowledgeDocument unknownDocument = KnowledgeDocument.builder()
                .documentId("financial-unknown")
                .title("行业研究")
                .rawContent("没有可识别的证券或年份。")
                .build();
        HierarchicalDocumentChunker.ParentDraft unknownParent = chunker.chunk(unknownDocument, "version-unknown")
                .getParents().getFirst();
        assertNull(unknownParent.getStockCode());
        assertNull(unknownParent.getYear());
    }

    @Test
    void createsExtractiveSummary() {
        String firstParagraph = "首个有效段落说明本章节聚焦贵州茅台经营质量、行业竞争格局与未来战略，供投资研究和风险判断使用。"
                + "研究范围覆盖产品结构、渠道效率、品牌势能、行业供需、公司治理和长期现金回报。".repeat(7);
        String revenueSentence = "公司营业收入同比增长25%，归母净利润增长18%，毛利率继续提升，核心财务指标显示盈利能力改善。";
        String valuationSentence = "当前估值处于近三年中枢，市盈率约25倍，现金流充裕且经营活动现金流同比增长30%，具备估值支撑。";
        String riskSentence = "需要关注渠道库存上升、消费需求下降、商誉减值以及潜在诉讼风险，这些风险可能影响后续业绩。";
        String longContent = firstParagraph + "\n\n" + "背景信息".repeat(350) + "。"
                + revenueSentence + revenueSentence + valuationSentence + riskSentence + "补充说明".repeat(350) + "。";
        KnowledgeDocument longDocument = KnowledgeDocument.builder()
                .documentId("summary-long")
                .title("贵州茅台研究")
                .rawContent("# 财务分析\n" + longContent)
                .build();

        HierarchicalDocumentChunker.ParentDraft longParent = chunker.chunk(longDocument, "version-summary")
                .getParents().getFirst();
        String summary = longParent.getSummary();
        assertTrue(summary.length() >= 400 && summary.length() <= 600);
        assertTrue(summary.contains("财务分析"));
        assertTrue(summary.contains(firstParagraph));
        assertTrue(summary.contains(revenueSentence));
        assertTrue(summary.contains(valuationSentence));
        assertTrue(summary.contains(riskSentence));
        assertTrue(summary.indexOf(revenueSentence) < summary.indexOf(valuationSentence));
        assertTrue(summary.indexOf(valuationSentence) < summary.indexOf(riskSentence));
        assertEquals(summary.indexOf(revenueSentence), summary.lastIndexOf(revenueSentence));
        assertTrue(chunker.chunk(longDocument, "version-summary").getChildren().stream()
                .noneMatch(child -> child.getEmbeddingText().contains(summary)));

        KnowledgeDocument shortDocument = KnowledgeDocument.builder()
                .documentId("summary-short")
                .title("短文")
                .rawContent("# 简短章节\n营业收入同比增长。")
                .build();
        assertNull(chunker.chunk(shortDocument, "version-short").getParents().getFirst().getSummary());
    }
}
