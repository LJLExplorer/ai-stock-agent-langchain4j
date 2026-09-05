package com.ljl.ai.tools;

import com.ljl.ai.client.NewsSearchClient;
import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.research.AnalysisContext;
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
        log.info("检索股票资讯, stock: {}, queryLength: {}, days: {}", stock,
                query == null ? 0 : query.length(), days);
        return ToolResultExecutor.execute("NEWS_SEARCH_ERROR",
                () -> newsSearchClient.search(stock, query, days, 5));
    }

    /** 工作流专用入口，按发布时间限制新闻可见范围。 */
    public ToolResult<List<NewsSearchClient.NewsItem>> searchStockNewsAndAnnouncements(
            String stock, String query, int days, AnalysisContext context) {
        if (context == null) {
            throw new IllegalArgumentException("AnalysisContext 不能为空");
        }
        log.info("按分析时点检索股票资讯, stock: {}, analysisDate: {}, queryLength: {}, days: {}",
                stock, context.analysisDate(), query == null ? 0 : query.length(), days);
        return ToolResultExecutor.execute("NEWS_SEARCH_ERROR",
                () -> newsSearchClient.search(stock, query, days, 5, context.analysisDate()));
    }
}
