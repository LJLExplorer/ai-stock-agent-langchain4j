package com.ljl.ai.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class NewsSearchClient {
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build();

    @Value("${news-search.tavily-api-key:${news-search.tavily-api-keys:}}")
    private String configuredTavilyKey;

    @Value("${news-search.serpapi-api-key:${news-search.serpapi-api-keys:}}")
    private String configuredSerpApiKey;

    @Value("${news-search.relevance-threshold:0.45}")
    private double relevanceThreshold = 0.45;

    @Value("${news-search.max-retries:3}")
    private int maxRetries = 3;

    @Value("${news-search.min-relevant-results:3}")
    private int minRelevantResults = 3;

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    public List<NewsItem> search(String stock, String query, int days, int maxResults) throws Exception {
        String tavilyKey = firstConfiguredKey(configuredTavilyKey, "TAVILY_API_KEYS", "TAVILY_API_KEY");
        if (!tavilyKey.isBlank()) {
            return searchWithRetries((searchQuery, resultLimit) -> searchTavily(tavilyKey, stock, searchQuery,
                    days, resultLimit), stock, query, maxResults);
        }
        String serpKey = firstConfiguredKey(configuredSerpApiKey, "SERPAPI_API_KEYS", "SERPAPI_API_KEY");
        if (!serpKey.isBlank()) {
            return searchWithRetries((searchQuery, resultLimit) -> searchSerpApi(serpKey, stock, searchQuery,
                    resultLimit), stock, query, maxResults);
        }
        throw new IllegalStateException("未配置 Tavily 或 SerpAPI 任一新闻搜索 API Key");
    }

    private List<NewsItem> searchWithRetries(NewsSearcher searcher, String stock, String query,
                                             int maxResults) throws Exception {
        Map<String, NewsItem> collected = new LinkedHashMap<>();
        int retryCount = Math.max(0, maxRetries);
        int resultLimit = Math.max(maxResults, minRelevantResults);
        for (int attempt = 0; attempt <= retryCount && collected.size() < minRelevantResults; attempt++) {
            String searchQuery = attempt == 0 ? query : broadenQuery(query, attempt);
            if (attempt > 0) {
                log.info("新闻相关结果不足，执行第 {} 次扩展关键词重查, stock: {}, queryLength: {}", attempt,
                        stock, searchQuery.length());
            }
            List<NewsItem> filtered = filterByRelevance(searcher.search(searchQuery, resultLimit), stock, query);
            filtered.forEach(item -> collected.putIfAbsent(resultKey(item), item));
        }
        log.info("新闻多轮检索完成, attempts: {}, relevantResults: {}, requiredResults: {}",
                Math.min(retryCount + 1, collected.size() < minRelevantResults ? retryCount + 1 : retryCount + 1),
                collected.size(), minRelevantResults);
        return new ArrayList<>(collected.values());
    }

    private String broadenQuery(String query, int attempt) {
        String[] expansions = {" 最新新闻 公告", " 最新财报 经营业绩", " 行业动态 公司公告"};
        return (query == null ? "" : query) + expansions[Math.min(attempt - 1, expansions.length - 1)];
    }

    private String resultKey(NewsItem item) {
        return item.url() == null || item.url().isBlank() ? item.title() : item.url();
    }

    private List<NewsItem> searchTavily(String key, String stock, String query, int days, int maxResults) throws Exception {
        JSONObject body = new JSONObject();
        body.put("api_key", key);
        body.put("query", stock + " " + query);
        body.put("topic", "news");
        body.put("days", Math.max(1, days));
        body.put("search_depth", "advanced");
        body.put("max_results", Math.max(1, maxResults));
        Request request = new Request.Builder().url("https://api.tavily.com/search")
                .post(RequestBody.create(body.toJSONString(), JSON_TYPE)).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("Tavily HTTP " + response.code());
            JSONArray results = JSON.parseObject(response.body().string()).getJSONArray("results");
            return parseResults(results);
        }
    }

    private List<NewsItem> searchSerpApi(String key, String stock, String query, int maxResults) throws Exception {
        String url = "https://serpapi.com/search.json?engine=google_news&q="
                + java.net.URLEncoder.encode(stock + " " + query, java.nio.charset.StandardCharsets.UTF_8)
                + "&api_key=" + java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8);
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("SerpAPI HTTP " + response.code());
            JSONArray results = JSON.parseObject(response.body().string()).getJSONArray("news_results");
            return parseResults(results);
        }
    }

    private List<NewsItem> parseResults(JSONArray results) {
        List<NewsItem> items = new ArrayList<>();
        if (results == null) return items;
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            if (item == null) continue;
            JSONObject source = item.getJSONObject("source");
            items.add(new NewsItem(item.getString("title"), value(item, "content", "snippet"),
                    value(item, "url", "link"), source == null ? "" : source.getString("name"),
                    item.getString("published_date"), item.getDouble("score")));
        }
        return items;
    }

    /** 对搜索供应商的关键词召回结果做一次语义过滤，避免仅因提到关键词而进入工具结果。 */
    List<NewsItem> filterByRelevance(List<NewsItem> items, String stock, String query) {
        if (items == null || items.isEmpty() || embeddingModel == null) {
            if (embeddingModel == null) {
                log.warn("新闻结果未执行语义相关性过滤，EmbeddingModel 未配置");
            }
            return items == null ? List.of() : items;
        }
        try {
            String searchText = (stock + " " + query).trim();
            Embedding queryEmbedding = embeddingModel.embed(searchText).content();
            List<NewsItem> filtered = new ArrayList<>();
            for (NewsItem item : items) {
                String documentText = (item.title() + "\n" + item.summary()).trim();
                if (documentText.isBlank()) {
                    continue;
                }
                Embedding documentEmbedding = embeddingModel.embed(documentText).content();
                double score = cosine(queryEmbedding.vector(), documentEmbedding.vector());
                if (score >= relevanceThreshold) {
                    filtered.add(item.withRelevanceScore(score));
                } else {
                    log.debug("过滤低相关度新闻, score: {}, threshold: {}", score, relevanceThreshold);
                }
            }
            log.info("新闻语义过滤完成, 原始结果: {}, 保留结果: {}, threshold: {}", items.size(), filtered.size(),
                    relevanceThreshold);
            return filtered;
        } catch (RuntimeException exception) {
            log.warn("新闻语义过滤失败，保留供应商结果, errorType={}",
                    exception.getClass().getSimpleName());
            return items;
        }
    }

    private double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length || left.length == 0) {
            return 0D;
        }
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return leftNorm == 0D || rightNorm == 0D ? 0D : dot / Math.sqrt(leftNorm * rightNorm);
    }

    @FunctionalInterface
    private interface NewsSearcher {
        List<NewsItem> search(String query, int maxResults) throws Exception;
    }

    private static String value(JSONObject object, String primary, String fallback) {
        String value = object.getString(primary);
        return value == null || value.isBlank() ? object.getString(fallback) : value;
    }

    private static String firstConfiguredKey(String configuredValue, String... names) {
        if (configuredValue != null && !configuredValue.isBlank()) {
            return configuredValue.trim().split(",")[0].trim();
        }
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) return value.split(",")[0].trim();
        }
        return "";
    }

    public record NewsItem(String title, String summary, String url, String source, String publishedAt,
                           Double relevanceScore) {
        public NewsItem(String title, String summary, String url, String source, String publishedAt) {
            this(title, summary, url, source, publishedAt, null);
        }

        private NewsItem withRelevanceScore(double score) {
            return new NewsItem(title, summary, url, source, publishedAt, score);
        }
    }
}
