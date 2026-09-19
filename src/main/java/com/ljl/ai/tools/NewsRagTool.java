package com.ljl.ai.tools;

import com.ljl.ai.client.NewsSearchClient;
import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.research.AnalysisContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.net.URI;

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
                () -> validated(newsSearchClient.search(stock, query, days, 5)));
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
                () -> validated(newsSearchClient.search(stock, query, days, 5, context.analysisDate())));
    }

    public ToolResult<List<NewsSearchClient.NewsItem>> searchOfficialAnnouncements(
            String stock, String query, int days) {
        log.info("检索官方公告, stock: {}, queryLength: {}, days: {}", stock,
                query == null ? 0 : query.length(), days);
        return ToolResultExecutor.execute("NEWS_SEARCH_ERROR",
                () -> validated(newsSearchClient.searchOfficial(stock, query, days, 5, java.time.LocalDate.now())));
    }

    public ToolResult<List<NewsSearchClient.NewsItem>> searchOfficialAnnouncements(
            String stock, String query, int days, AnalysisContext context) {
        if (context == null) throw new IllegalArgumentException("AnalysisContext 不能为空");
        log.info("按分析时点检索官方公告, stock: {}, analysisDate: {}, queryLength: {}, days: {}",
                stock, context.analysisDate(), query == null ? 0 : query.length(), days);
        return ToolResultExecutor.execute("NEWS_SEARCH_ERROR",
                () -> validated(newsSearchClient.searchOfficial(stock, query, days, 5, context.analysisDate())));
    }

    private List<NewsSearchClient.NewsItem> validated(List<NewsSearchClient.NewsItem> items) {
        if (items == null || items.isEmpty()) return List.of();
        List<NewsSearchClient.NewsItem> accepted = items.stream().filter(item -> {
            String reason = invalidReason(item);
            if (reason != null) {
                log.info("news_candidate_rejected reason={}", reason);
                return false;
            }
            return true;
        }).toList();
        log.info("news_tool_candidates_validated inputCount={}, acceptedCount={}", items.size(), accepted.size());
        return accepted;
    }

    private String invalidReason(NewsSearchClient.NewsItem item) {
        if (item == null) return "ITEM_NULL";
        if (blank(item.title())) return "TITLE_MISSING";
        if (blank(item.summary())) return "SUMMARY_MISSING";
        if (blank(item.source())) return "SOURCE_MISSING";
        try {
            URI uri = URI.create(item.url());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                return "URL_INVALID";
            }
        } catch (RuntimeException exception) {
            return "URL_INVALID";
        }
        return null;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
