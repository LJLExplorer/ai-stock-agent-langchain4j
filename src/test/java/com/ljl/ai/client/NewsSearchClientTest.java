package com.ljl.ai.client;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewsSearchClientTest {

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
