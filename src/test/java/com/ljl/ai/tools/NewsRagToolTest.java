package com.ljl.ai.tools;

import com.ljl.ai.client.NewsSearchClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewsRagToolTest {

    @Test
    void filtersIncompleteCandidatesBeforeReturningToolResult() throws Exception {
        NewsSearchClient client = mock(NewsSearchClient.class);
        var valid = new NewsSearchClient.NewsItem("公告", "完整摘要", "https://example.com/a", "交易所", "2026-09-18");
        var missingSummary = new NewsSearchClient.NewsItem("新闻", " ", "https://example.com/b", "媒体", "2026-09-18");
        var invalidUrl = new NewsSearchClient.NewsItem("新闻", "摘要", "file:///tmp/a", "媒体", "2026-09-18");
        when(client.search("600519.SH", "业绩", 7, 5)).thenReturn(List.of(valid, missingSummary, invalidUrl));

        var result = new NewsRagTool(client).searchStockNewsAndAnnouncements("600519.SH", "业绩", 7);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsExactly(valid);
    }
}
