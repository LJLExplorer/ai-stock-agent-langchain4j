package com.ljl.ai.client;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.research.EvidencePackBuilder;
import com.ljl.ai.research.FinancialFact;
import com.ljl.ai.planner.StockAnalysisTask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewsSearchClientTest {

    @Test
    void shouldBindDisclosureDatesToIndividualOfficialLinksNotTheListingPage() {
        NewsSearchClient client = new NewsSearchClient();
        String raw = "* [贵州茅台2026年半年度报告](/report/2026.pdf \"贵州茅台2026年半年度报告\")2026-08-15\n"
                + "* 2026-04-17 [贵州茅台2025年年度报告](/report/2025.pdf)\n"
                + "* [日期缺失](/undated.pdf)\n* [伪链接](https://evil.test/report.pdf)2026-04-17\n";
        var results = client.disclosuresFromListing("https://www.moutai.com.cn/reports/index.html", raw,
                client.officialDomains("600519.SH"));
        assertEquals(List.of("https://www.moutai.com.cn/report/2026.pdf", "https://www.moutai.com.cn/report/2025.pdf"),
                results.stream().map(NewsSearchClient.NewsItem::url).toList());
        assertEquals(List.of("2026-08-15", "2026-04-17"), results.stream().map(NewsSearchClient.NewsItem::publishedAt).toList());
        assertEquals(List.of(), client.disclosuresFromListing("https://evil.test/index.html", raw, client.officialDomains("600519.SH")));
        var embedded = client.disclosuresFromListing("https://www.moutai.com.cn/reports/index.html",
                "* [贵州茅台2026年半年度报告2026-08-15](/report/2026.pdf)", client.officialDomains("600519.SH"));
        assertEquals("2026-08-15", embedded.getFirst().publishedAt());
        assertEquals("贵州茅台2026年半年度报告", embedded.getFirst().title());
    }

    @Test
    void shouldIncludeOfficialReportsEvenWhenMediaResultsAlreadyMeetTarget() throws Exception {
        NewsSearchClient client = new NewsSearchClient();
        List<Boolean> searches = new java.util.ArrayList<>();
        LocalDate date = LocalDate.of(2026, 9, 6);
        var results = client.searchWithRetries((query, limit, official) -> {
            searches.add(official);
            if (official) return List.of(new NewsSearchClient.NewsItem("贵州茅台临时公告", "公司经营数据",
                    "https://www.moutai.com.cn/report.html", "贵州茅台官网", "Thu, 20 Aug 2026 08:00:00 GMT"));
            return List.of(
                    new NewsSearchClient.NewsItem("贵州茅台新闻一", "摘要", "https://example.test/1", "媒体", "2026-09-04"),
                    new NewsSearchClient.NewsItem("贵州茅台新闻二", "摘要", "https://example.test/2", "媒体", "2026-09-04"),
                    new NewsSearchClient.NewsItem("贵州茅台新闻三", "摘要", "https://example.test/3", "媒体", "2026-09-04"));
        }, List.of("600519", "贵州茅台"), "财报与新闻", 5, date, 30, client.officialDomains("600519.SH"));

        assertEquals(List.of(false, true), searches);
        assertEquals(4, results.size());
        assertEquals("https://www.moutai.com.cn/report.html", results.getFirst().url());
        assertEquals(FinancialFact.TemporalStatus.VERIFIED, results.getFirst().temporalStatus());
    }

    @Test
    void shouldKeepDomainAndFutureDateGuardsForOfficialSearch() throws Exception {
        NewsSearchClient client = new NewsSearchClient();
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        ReflectionTestUtils.setField(client, "embeddingModel", embedding);
        ReflectionTestUtils.setField(client, "maxRetries", 1);
        var results = client.searchWithRetries((query, limit, official) -> official ? List.of(
                new NewsSearchClient.NewsItem("600519 报告", "摘要", "https://sse.com.cn.evil.test/report", "伪官网", "2026-04-17"),
                new NewsSearchClient.NewsItem("600519 报告", "摘要", "https://www.sse.com.cn/report", "上交所", "2027-04-17"),
                new NewsSearchClient.NewsItem("600519 报告", "摘要", "https://www.sse.com.cn/undated", "上交所", null),
                new NewsSearchClient.NewsItem("600519 报告", "摘要", "https://www.sse.com.cn/valid", "上交所", "2026-08-17")) : List.of(),
                List.of("600519"), "报告", 5, LocalDate.of(2026, 9, 6), 30, client.officialDomains("600519.SH"));
        assertEquals(List.of("https://www.sse.com.cn/valid"), results.stream().map(NewsSearchClient.NewsItem::url).toList());
        org.mockito.Mockito.verify(embedding, org.mockito.Mockito.never()).embed(any(String.class));
    }

    @Test
    void shouldUseUrlHostWhenProviderOmitsSourceName() {
        NewsSearchClient client = new NewsSearchClient();
        var raw = com.alibaba.fastjson2.JSON.parseArray("""
                [{"title":"贵州茅台公告","content":"摘要","url":"https://finance.example.com/news/1",
                  "published_date":"2026-09-04"}]
                """);

        @SuppressWarnings("unchecked")
        List<NewsSearchClient.NewsItem> items = ReflectionTestUtils.invokeMethod(client, "parseResults", raw);

        assertEquals("finance.example.com", items.getFirst().source());
    }

    @Test
    void shouldOnlyExtractExplicitPublicationDateFromOriginalContent() {
        NewsSearchClient client = new NewsSearchClient();
        var item = com.alibaba.fastjson2.JSON.parseObject("{\"raw_content\":\"报告期：2025-12-31\\n发布时间：2026-04-17\"}");
        assertEquals("2026-04-17", ReflectionTestUtils.invokeMethod(client, "publicationDate", item));
        item.put("raw_content", "报告期：2025-12-31");
        org.junit.jupiter.api.Assertions.assertNull(ReflectionTestUtils.invokeMethod(client, "publicationDate", item));
        item.put("raw_content", "文章来源：融媒体中心\n发布时间：2026年08月21日");
        assertEquals("2026年08月21日", ReflectionTestUtils.invokeMethod(client, "publicationDate", item));
    }

    @Test
    void shouldSearchCompanyNewsWithoutCopyingSkillInstructions() {
        String query = new NewsSearchClient().newsQuery("使用 GMMA skill 教程分析600519，看看分红和回购");
        assertEquals("公司新闻 公告 分红 回购", query);
    }

    @Test
    void shouldRejectTutorialSkillUnrelatedUndatedAndStaleResults() {
        NewsSearchClient client = new NewsSearchClient();
        LocalDate date = LocalDate.of(2026, 9, 6);
        List<NewsSearchClient.NewsItem> raw = List.of(
                new NewsSearchClient.NewsItem("600519 分红公告", "公司发布权益分派公告", "https://example.test/news/1", "财经媒体", "2026-09-04"),
                new NewsSearchClient.NewsItem("600519 GMMA skill", "股票分析教程", "https://github.com/test/skills", "GitHub", "2026-09-04"),
                new NewsSearchClient.NewsItem("600519 使用教程", "学习指标方法", "https://example.test/tutorial", "教程站", "2026-09-04"),
                new NewsSearchClient.NewsItem("600519 财报", "无日期信息", "https://example.test/unknown", "财经媒体", null),
                new NewsSearchClient.NewsItem("600519 旧闻", "旧事件", "https://example.test/old", "财经媒体", "2025-09-04"),
                new NewsSearchClient.NewsItem("其他公司公告", "经营动态", "https://example.test/other", "财经媒体", "2026-09-04"),
                new NewsSearchClient.NewsItem("600519 财报", "无原文链接", "javascript:alert(1)", "财经媒体", "2026-09-04"));

        List<NewsSearchClient.NewsItem> result = client.filterNewsCandidates(client.filterByPublishedAt(raw, date),
                "600519.SH", date, 30);

        assertEquals(List.of("https://example.test/news/1"), result.stream().map(NewsSearchClient.NewsItem::url).toList());
    }

    @Test
    void shouldPreserveRecognizedDateAcrossNewsAndEvidenceBoundary() {
        NewsSearchClient client = new NewsSearchClient();
        LocalDate date = LocalDate.of(2026, 9, 6);
        AnalysisContext context = new AnalysisContext("600519.SH", date, AnalysisContext.ResearchMode.DEEP,
                "exec", "trace", "user", "session");
        for (String supplied : List.of("2026-09-04", "2026-09-04 10:30:00", "2026-09-04T10:30:00+08:00",
                "Fri, 04 Sep 2026 08:00:00 GMT", "Sep 4, 2026", "September 4, 2026", "2026年09月04日")) {
            List<NewsSearchClient.NewsItem> result = client.filterByPublishedAt(List.of(new NewsSearchClient.NewsItem(
                    "600519 公告", "公告摘要", "https://example.test/news", "交易所", supplied)), date);
            var facts = new EvidencePackBuilder().map(StockAnalysisTask.NEWS_ANALYSIS, result, context);
            assertEquals(FinancialFact.TemporalStatus.VERIFIED, facts.get(0).temporalStatus());
            assertEquals(LocalDate.of(2026, 9, 4), facts.get(0).asOf());
        }
    }

    @Test
    void shouldRemoveKeywordOnlyNewsWithLowSemanticSimilarity() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(String.class))).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return Response.from(new Embedding(text.contains("经营业绩") ? new float[]{1F, 0F} : new float[]{0F, 1F}));
        });
        NewsSearchClient client = new NewsSearchClient();
        ReflectionTestUtils.setField(client, "embeddingModel", embeddingModel);

        List<NewsSearchClient.NewsItem> result = client.filterByRelevance(List.of(
                new NewsSearchClient.NewsItem("贵州茅台经营业绩公告", "公司经营业绩增长", "url-1", "source", "today"),
                new NewsSearchClient.NewsItem("市场评论提到贵州茅台", "文章主要讨论其他公司", "url-2", "source", "today")),
                "贵州茅台", "经营业绩");

        assertEquals(List.of("url-1"), result.stream().map(NewsSearchClient.NewsItem::url).toList());
    }

    @Test
    void shouldRejectFutureNewsAndMarkUnknownPublicationTime() {
        NewsSearchClient client = new NewsSearchClient();

        List<NewsSearchClient.NewsItem> result = client.filterByPublishedAt(List.of(
                new NewsSearchClient.NewsItem("已发布", "内容", "url-1", "source", "2026-03-01T08:00:00Z"),
                new NewsSearchClient.NewsItem("未来新闻", "内容", "url-2", "source", "2026-03-16T00:00:00Z"),
                new NewsSearchClient.NewsItem("日期未知", "内容", "url-3", "source", "刚刚")),
                LocalDate.of(2026, 3, 15));

        assertEquals(List.of("url-1", "url-3"), result.stream().map(NewsSearchClient.NewsItem::url).toList());
        assertEquals("VERIFIED", result.get(0).temporalStatus().name());
        assertEquals("UNKNOWN", result.get(1).temporalStatus().name());
    }
}
