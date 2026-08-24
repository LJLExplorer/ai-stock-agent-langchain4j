package com.ljl.ai.agent.tools;

import com.ljl.ai.agent.data.NewsSearchClient;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NewsRagTool {
    private final NewsSearchClient newsSearchClient;

    public NewsRagTool(NewsSearchClient newsSearchClient) {
        this.newsSearchClient = newsSearchClient;
    }

    @Tool(name = "searchStockNewsAndAnnouncements", value = "检索股票最近新闻、公告、财报和行业报告，返回来源与摘要")
    public String searchStockNewsAndAnnouncements(@P("股票代码或公司名称") String stock,
                                                  @P("检索问题或关键词") String query,
                                                  @P("检索最近多少天") int days) {
        log.info("检索股票资讯, stock: {}, query: {}, days: {}", stock, query, days);
        try {
            return "真实资讯检索（Tavily/SerpAPI）\n" + newsSearchClient.search(stock, query, days, 5);
        } catch (Exception e) {
            log.error("资讯检索失败, stock: {}", stock, e);
            return "资讯检索失败：" + e.getMessage();
        }
    }
}
