package com.ljl.ai.agent.tools;

import com.ljl.ai.agent.data.NewsSearchClient;
import com.ljl.ai.agent.model.dto.ToolResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class NewsRagTool {
    private final NewsSearchClient newsSearchClient;

    public NewsRagTool(NewsSearchClient newsSearchClient) {
        this.newsSearchClient = newsSearchClient;
    }

    @Tool(name = "searchStockNewsAndAnnouncements", value = "检索股票最近新闻、公告、财报和行业报告，返回来源与摘要")
    public ToolResult<List<NewsSearchClient.NewsItem>> searchStockNewsAndAnnouncements(
            @P("股票代码或公司名称") String stock,
            @P("检索问题或关键词") String query,
            @P("检索最近多少天") int days) {
        log.info("检索股票资讯, stock: {}, query: {}, days: {}", stock, query, days);
        return ToolResultExecutor.execute("NEWS_SEARCH_ERROR",
                () -> newsSearchClient.search(stock, query, days, 5));
    }
}
