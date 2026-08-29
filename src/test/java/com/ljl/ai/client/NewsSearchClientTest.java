package com.ljl.ai.client;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
}
