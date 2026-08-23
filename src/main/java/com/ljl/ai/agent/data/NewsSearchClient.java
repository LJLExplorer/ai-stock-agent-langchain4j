package com.ljl.ai.agent.data;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class NewsSearchClient {
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build();

    public List<NewsItem> search(String stock, String query, int days, int maxResults) throws Exception {
        String tavilyKey = firstKey("TAVILY_API_KEYS", "TAVILY_API_KEY");
        if (!tavilyKey.isBlank()) {
            return searchTavily(tavilyKey, stock, query, days, maxResults);
        }
        String serpKey = firstKey("SERPAPI_API_KEYS", "SERPAPI_API_KEY");
        if (!serpKey.isBlank()) {
            return searchSerpApi(serpKey, stock, query, maxResults);
        }
        throw new IllegalStateException("未配置 TAVILY_API_KEYS 或 SERPAPI_API_KEYS");
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
                    item.getString("published_date")));
        }
        return items;
    }

    private static String value(JSONObject object, String primary, String fallback) {
        String value = object.getString(primary);
        return value == null || value.isBlank() ? object.getString(fallback) : value;
    }

    private static String firstKey(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) return value.split(",")[0].trim();
        }
        return "";
    }

    public record NewsItem(String title, String summary, String url, String source, String publishedAt) {}
}
