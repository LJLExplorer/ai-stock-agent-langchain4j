package com.ljl.ai.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.research.FinancialFact;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.net.URI;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class NewsSearchClient {
    private static final int NEWS_LOOKBACK_DAYS = 366;
    private static final int RECENT_NEWS_DAYS = 30;
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");
    private static final Pattern NON_NEWS = Pattern.compile(
            "(?i)\\bskills?\\b|\\breadme\\b|安装教程|使用教程|开发教程|提示词模板|技能说明|代码仓库");
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

    @Autowired(required = false)
    private MarketDataClient marketDataClient;

    /** 经核对的上市公司域名；可配置更多 code=host,host，条目之间用分号分隔。 */
    @Value("${news-search.issuer-domains:600519=moutai.com.cn,moutaichina.com}")
    private String issuerDomains = "600519=moutai.com.cn,moutaichina.com";

    @Value("${news-search.issuer-listings:600519=https://www.moutai.com.cn/mtgf/tzzgx/cwbg/index.html}")
    private String issuerListings = "600519=https://www.moutai.com.cn/mtgf/tzzgx/cwbg/index.html";

    public List<NewsItem> search(String stock, String query, int days, int maxResults) throws Exception {
        return search(stock, query, days, maxResults, LocalDate.now());
    }

    public List<NewsItem> search(String stock, String query, int days, int maxResults,
                                 LocalDate analysisDate) throws Exception {
        List<String> entities = stockEntities(stock);
        String searchStock = String.join(" ", entities);
        List<String> domains = officialDomains(stock);
        String tavilyKey = firstConfiguredKey(configuredTavilyKey, "TAVILY_API_KEYS", "TAVILY_API_KEY");
        if (!tavilyKey.isBlank()) {
            java.util.concurrent.atomic.AtomicBoolean listingAttempted = new java.util.concurrent.atomic.AtomicBoolean();
            return searchWithRetries((searchQuery, resultLimit, official) -> {
                if (official && !listingAttempted.getAndSet(true)) {
                    List<NewsItem> direct = extractIssuerListing(tavilyKey, stock, domains);
                    if (!direct.isEmpty()) return direct;
                }
                return searchTavily(tavilyKey, official ? entities.getLast() : searchStock, searchQuery,
                        days, resultLimit, analysisDate, official, domains);
            }, entities, query, maxResults, analysisDate, days, domains);
        }
        String serpKey = firstConfiguredKey(configuredSerpApiKey, "SERPAPI_API_KEYS", "SERPAPI_API_KEY");
        if (!serpKey.isBlank()) {
            return searchWithRetries((searchQuery, resultLimit, official) -> searchSerpApi(serpKey, searchStock, searchQuery,
                    resultLimit, official, domains), entities, query, maxResults, analysisDate, days, domains);
        }
        throw new IllegalStateException("未配置 Tavily 或 SerpAPI 任一新闻搜索 API Key");
    }

    /** 恢复阶段只检索交易所、巨潮和发行人域名，避免再次消费同一批媒体候选。 */
    public List<NewsItem> searchOfficial(String stock, String query, int days, int maxResults,
                                         LocalDate analysisDate) throws Exception {
        List<String> entities = stockEntities(stock);
        List<String> domains = officialDomains(stock);
        String tavilyKey = firstConfiguredKey(configuredTavilyKey, "TAVILY_API_KEYS", "TAVILY_API_KEY");
        if (!tavilyKey.isBlank()) {
            java.util.concurrent.atomic.AtomicBoolean listingAttempted = new java.util.concurrent.atomic.AtomicBoolean();
            return searchOfficialWithRetries((searchQuery, resultLimit, official) -> {
                if (!listingAttempted.getAndSet(true)) {
                    List<NewsItem> direct = extractIssuerListing(tavilyKey, stock, domains);
                    if (!direct.isEmpty()) return direct;
                }
                return searchTavily(tavilyKey, entities.getLast(), searchQuery, days,
                        resultLimit, analysisDate, true, domains);
            }, entities, maxResults, analysisDate, days, domains);
        }
        String serpKey = firstConfiguredKey(configuredSerpApiKey, "SERPAPI_API_KEYS", "SERPAPI_API_KEY");
        if (!serpKey.isBlank()) {
            return searchOfficialWithRetries((searchQuery, resultLimit, official) -> searchSerpApi(
                    serpKey, String.join(" ", entities), searchQuery, resultLimit, true, domains),
                    entities, maxResults, analysisDate, days, domains);
        }
        throw new IllegalStateException("未配置 Tavily 或 SerpAPI 任一新闻搜索 API Key");
    }

    private List<NewsItem> searchOfficialWithRetries(NewsSearcher searcher, List<String> entities,
                                                      int maxResults, LocalDate analysisDate,
                                                      int days, List<String> domains) throws Exception {
        Map<String, NewsItem> collected = new LinkedHashMap<>();
        List<String> queries = List.of("财务报告 投资者关系", "公司公告 经营业绩", "业绩说明会 重大事项");
        Exception lastFailure = null;
        int attempts = Math.min(queries.size(), Math.max(1, maxRetries + 1));
        for (int attempt = 0; attempt < attempts && collected.size() < Math.max(1, maxResults); attempt++) {
            try {
                List<NewsItem> items = filterByPublishedAt(
                        searcher.search(queries.get(attempt), Math.max(maxResults, minRelevantResults), true), analysisDate);
                filterNewsCandidates(items, entities, analysisDate, days).stream()
                        .filter(item -> matchesDomain(item.url(), domains))
                        .forEach(item -> collected.putIfAbsent(resultKey(item), item));
            } catch (Exception exception) {
                lastFailure = exception;
                log.warn("news_source_search_failed sourceType=OFFICIAL, errorType={}",
                        exception.getClass().getSimpleName());
            }
        }
        if (collected.isEmpty() && lastFailure != null) throw lastFailure;
        log.info("official_news_search_finished attempts={}, acceptedCount={}", attempts, collected.size());
        return collected.values().stream().limit(Math.max(0, maxResults)).toList();
    }

    private List<NewsItem> extractIssuerListing(String key, String stock, List<String> domains) {
        String code = stock.trim().replaceFirst("(?i)\\.(SH|SZ|BJ)$", "");
        List<String> urls = new ArrayList<>();
        for (String entry : issuerListings.split(";")) {
            String[] pair = entry.trim().split("=", 2);
            if (pair.length == 2 && pair[0].trim().equals(code)
                    && isNewsUrl(pair[1].trim()) && matchesDomain(pair[1].trim(), domains)) urls.add(pair[1].trim());
        }
        if (urls.isEmpty()) return List.of();
        JSONObject body = new JSONObject();
        body.put("urls", urls.stream().limit(2).toList());
        body.put("format", "markdown");
        body.put("extract_depth", "basic");
        Request request = new Request.Builder().url("https://api.tavily.com/extract")
                .header("Authorization", "Bearer " + key).post(RequestBody.create(body.toJSONString(), JSON_TYPE)).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("官方目录提取 HTTP " + response.code());
            JSONArray results = JSON.parseObject(response.body().string()).getJSONArray("results");
            List<NewsItem> items = new ArrayList<>();
            if (results != null) for (int i = 0; i < results.size(); i++) {
                JSONObject item = results.getJSONObject(i);
                items.addAll(disclosuresFromListing(item.getString("url"), item.getString("raw_content"), domains));
            }
            log.info("official_listing_extracted sourceCount={}, disclosureCount={}", urls.size(), items.size());
            return items;
        } catch (Exception exception) {
            log.warn("official_listing_failed errorType={}", exception.getClass().getSimpleName());
            return List.of();
        }
    }

    List<NewsItem> searchWithRetries(NewsSearcher searcher, List<String> entities, String query,
                                    int maxResults, LocalDate analysisDate, int days, List<String> domains) throws Exception {
        Map<String, NewsItem> collected = new LinkedHashMap<>();
        int retryCount = Math.max(1, maxRetries);
        int resultLimit = Math.max(maxResults, minRelevantResults);
        int attempts = 0;
        String newsQuery = newsQuery(query);
        // 至少覆盖媒体新闻与官方披露两个入口，不能因媒体结果足够就跳过官方来源。
        for (int attempt = 0; attempt <= retryCount && (attempt < 2 || collected.size() < minRelevantResults
                || collected.values().stream().noneMatch(item -> matchesDomain(item.url(), domains))); attempt++) {
            attempts++;
            boolean official = attempt % 2 == 1;
            String searchQuery = official ? (attempt == 1 ? "财务报告 投资者关系" : "业绩说明会 经营业绩 公司公告")
                    : attempt == 0 ? newsQuery : broadenQuery(newsQuery, attempt);
            if (attempt > 0) {
                log.info("新闻相关结果不足，执行第 {} 次扩展关键词重查, stock: {}, queryLength: {}", attempt,
                        entities.getFirst(), searchQuery.length());
            }
            List<NewsItem> asOfItems;
            try {
                asOfItems = filterByPublishedAt(searcher.search(searchQuery, resultLimit, official), analysisDate);
            } catch (Exception exception) {
                log.warn("news_source_search_failed sourceType={}, errorType={}",
                        official ? "OFFICIAL" : "MEDIA", exception.getClass().getSimpleName());
                if (attempt == retryCount && collected.isEmpty()) throw exception;
                continue;
            }
            // 新闻任务与工作流使用同一个时间窗；历史财报由财务任务负责，避免先接纳再判过期。
            // 检索范围扩大到一年；最终选择时优先使用最近 30 天，只有没有近期新闻才回退到历史新闻。
            List<NewsItem> candidates = filterNewsCandidates(asOfItems, entities, analysisDate,
                    Math.max(days, NEWS_LOOKBACK_DAYS));
            if (official) candidates = candidates.stream().filter(item -> matchesDomain(item.url(), domains)).toList();
            // 官方披露已做域名、主体、文档类型和时点检查，不再用向量分数误删公司自己的报告。
            List<NewsItem> filtered = official ? candidates : filterByRelevance(candidates, String.join(" ", entities), newsQuery);
            filtered.forEach(item -> collected.putIfAbsent(resultKey(item), item));
        }
        log.info("新闻多轮检索完成, attempts: {}, relevantResults: {}, requiredResults: {}",
                attempts,
                collected.size(), minRelevantResults);
        int limit = Math.max(0, maxResults);
        Instant recentCutoff = analysisDate.minusDays(RECENT_NEWS_DAYS - 1L)
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        boolean hasRecent = collected.values().stream()
                .map(item -> parsePublishedAt(item.publishedAt()).orElse(null))
                .anyMatch(published -> published != null && !published.isBefore(recentCutoff));
        List<NewsItem> selectedPool = hasRecent
                ? collected.values().stream().filter(item -> parsePublishedAt(item.publishedAt())
                        .map(published -> !published.isBefore(recentCutoff)).orElse(false)).toList()
                : List.copyOf(collected.values());
        java.util.Comparator<NewsItem> newestFirst = java.util.Comparator.comparing(
                (NewsItem item) -> parsePublishedAt(item.publishedAt()).orElse(Instant.MIN)).reversed();
        List<NewsItem> official = selectedPool.stream().filter(item -> matchesDomain(item.url(), domains))
                .sorted(newestFirst.thenComparing(item -> item.title().contains("摘要") || item.title().contains("英文版")))
                .toList();
        List<NewsItem> selected = new ArrayList<>(official.stream().limit((limit + 1L) / 2).toList());
        selectedPool.stream().filter(item -> !matchesDomain(item.url(), domains)).sorted(newestFirst)
                .limit(Math.max(0, limit - selected.size())).forEach(selected::add);
        official.stream().filter(item -> !selected.contains(item)).limit(Math.max(0, limit - selected.size())).forEach(selected::add);
        return List.copyOf(selected);
    }

    private List<String> stockEntities(String stock) {
        if (stock == null || stock.isBlank()) throw new IllegalArgumentException("股票标识不能为空");
        String code = stock.trim().replaceFirst("(?i)\\.(SH|SZ|BJ)$", "");
        if (!code.matches("\\d{6}") || marketDataClient == null) return List.of(code);
        try {
            var quote = marketDataClient.getRealtimeQuote(stock);
            if (quote != null && quote.getName() != null && !quote.getName().isBlank()) {
                return List.of(code, quote.getName().trim());
            }
        } catch (Exception exception) {
            log.warn("news_company_name_resolution_failed errorType={}", exception.getClass().getSimpleName());
        }
        return List.of(code);
    }

    List<String> officialDomains(String stock) {
        List<String> domains = new ArrayList<>(List.of("cninfo.com.cn", "sse.com.cn", "szse.cn", "bse.cn"));
        String code = stock.trim().replaceFirst("(?i)\\.(SH|SZ|BJ)$", "");
        for (String entry : issuerDomains.split(";")) {
            String[] pair = entry.trim().split("=", 2);
            if (pair.length == 2 && pair[0].trim().equals(code)) {
                for (String host : pair[1].split(",")) {
                    if (host.trim().matches("[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")) domains.add(host.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return List.copyOf(domains);
    }

    private boolean matchesDomain(String url, List<String> domains) {
        try {
            String host = URI.create(url).getHost().toLowerCase(Locale.ROOT);
            return domains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** 检索词只保留新闻主题，不把用户的策略、教程或 skill 指令发给搜索引擎。 */
    String newsQuery(String query) {
        String question = query == null ? "" : query;
        List<String> topics = List.of("业绩", "财报", "分红", "回购", "增持", "减持", "监管", "诉讼", "重组");
        return "公司新闻 公告 " + String.join(" ", topics.stream().filter(question::contains).toList());
    }

    /** 语义相似度不是新闻真实性判断：先筛类型、主体、链接及发布时间，再做语义过滤。 */
    List<NewsItem> filterNewsCandidates(List<NewsItem> items, String stock, LocalDate analysisDate, int days) {
        if (items == null || stock == null || stock.isBlank()) return List.of();
        return filterNewsCandidates(items, List.of(stock.trim().replaceFirst("(?i)\\.(SH|SZ|BJ)$", "")), analysisDate, days);
    }

    private List<NewsItem> filterNewsCandidates(List<NewsItem> items, List<String> entities, LocalDate analysisDate, int days) {
        Instant earliest = analysisDate.minusDays(Math.max(1, days) - 1L)
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        Instant cutoff = analysisDate.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        List<NewsItem> accepted = new ArrayList<>();
        for (NewsItem item : items) {
            String text = item.title() + "\n" + item.summary();
            String reason = null;
            Optional<Instant> published = parsePublishedAt(item.publishedAt());
            if (item.title() == null || item.title().isBlank() || !isNewsUrl(item.url())
                    || NON_NEWS.matcher(text).find()) {
                reason = "NOT_NEWS_ARTICLE";
            // 搜索接口已经带入股票代码/公司名，媒体标题经常只写简称或事件，不再要求正文重复出现代码。
            // 主体匹配保留为检索召回层能力，避免因中文简称、别名或标题截断误杀真实新闻。
            } else if (published.isEmpty()) {
                reason = "PUBLICATION_TIME_UNKNOWN";
            } else if (published.get().isBefore(earliest) || !published.get().isBefore(cutoff)) {
                reason = "OUTSIDE_NEWS_WINDOW";
            }
            if (reason == null) {
                accepted.add(item);
            } else {
                log.info("news_candidate_rejected reason={}, publishedAt={}", reason,
                        String.valueOf(item.publishedAt()).replaceAll("[\\r\\n]", " "));
            }
        }
        log.info("news_candidates_filtered inputCount={}, acceptedCount={}", items.size(), accepted.size());
        return List.copyOf(accepted);
    }

    private boolean isNewsUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || !("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))) return false;
            host = host.toLowerCase(Locale.ROOT);
            for (String excluded : List.of("github.com", "raw.githubusercontent.com", "gitlab.com", "gitee.com", "skills.sh")) {
                if (host.equals(excluded) || host.endsWith("." + excluded)) return false;
            }
            return !NON_NEWS.matcher(uri.getPath() == null ? "" : uri.getPath()).find();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String broadenQuery(String query, int attempt) {
        String[] expansions = {" 最新新闻 公告", " 最新财报 经营业绩", " 行业动态 公司公告"};
        return (query == null ? "" : query) + expansions[Math.min(attempt - 1, expansions.length - 1)];
    }

    private String resultKey(NewsItem item) {
        return item.url() == null || item.url().isBlank() ? item.title() : item.url();
    }

    private List<NewsItem> searchTavily(String key, String stock, String query, int days, int maxResults,
                                      LocalDate analysisDate, boolean official, List<String> domains) throws Exception {
        JSONObject body = new JSONObject();
        body.put("api_key", key);
        body.put("query", stock + " " + query);
        body.put("topic", official ? "general" : "news");
        body.put("start_date", analysisDate.minusDays(NEWS_LOOKBACK_DAYS - 1L).toString());
        body.put("end_date", analysisDate.toString());
        if (official) {
            body.put("include_domains", domains);
            body.put("include_raw_content", true);
        }
        body.put("search_depth", "advanced");
        body.put("max_results", Math.max(1, maxResults));
        Request request = new Request.Builder().url("https://api.tavily.com/search")
                .post(RequestBody.create(body.toJSONString(), JSON_TYPE)).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("Tavily HTTP " + response.code());
            JSONArray results = JSON.parseObject(response.body().string()).getJSONArray("results");
            List<NewsItem> items = new ArrayList<>(parseResults(results));
            if (official && results != null) {
                for (int i = 0; i < results.size(); i++) {
                    JSONObject result = results.getJSONObject(i);
                    items.addAll(disclosuresFromListing(result.getString("url"), result.getString("raw_content"), domains));
                }
            }
            return items;
        }
    }

    private List<NewsItem> searchSerpApi(String key, String stock, String query, int maxResults,
                                       boolean official, List<String> domains) throws Exception {
        String domainQuery = official ? " (" + domains.stream().map(domain -> "site:" + domain)
                .collect(java.util.stream.Collectors.joining(" OR ")) + ")" : "";
        String url = "https://serpapi.com/search.json?engine=" + (official ? "google" : "google_news") + "&q="
                + java.net.URLEncoder.encode(stock + " " + query + domainQuery, java.nio.charset.StandardCharsets.UTF_8)
                + "&api_key=" + java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8);
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("SerpAPI HTTP " + response.code());
            JSONArray results = JSON.parseObject(response.body().string()).getJSONArray(official ? "organic_results" : "news_results");
            return parseResults(results);
        }
    }

    private List<NewsItem> parseResults(JSONArray results) {
        List<NewsItem> items = new ArrayList<>();
        if (results == null) return items;
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            if (item == null) continue;
            Object source = item.get("source");
            String url = value(item, "url", "link");
            String sourceName = source instanceof JSONObject object ? object.getString("name")
                    : source instanceof String name ? name : "";
            if (sourceName == null || sourceName.isBlank()) sourceName = sourceHost(url);
            items.add(new NewsItem(item.getString("title"), value(item, "content", "snippet"),
                    url, sourceName,
                    publicationDate(item), item.getDouble("score")));
        }
        return items;
    }

    private String sourceHost(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private String publicationDate(JSONObject item) {
        String supplied = value(item, "published_date", "date");
        if (supplied != null && !supplied.isBlank()) return supplied;
        // 只接受原文中明确标注的发布日期，不把报告期、行情日期或爬取时间冒充披露日期。
        String raw = String.valueOf(item.getString("raw_content")) + "\n" + value(item, "content", "snippet");
        var date = Pattern.compile("(?:发布时间|发布日期|公告日期)[：:][ \\t]*(\\d{4}(?:-\\d{2}-\\d{2}|年\\d{1,2}月\\d{1,2}日))").matcher(raw);
        return date.find() ? date.group(1) : null;
    }

    /** 官方目录把文件链接与披露日期列在同一条目中；不要把某条目的日期赋给整个目录。 */
    List<NewsItem> disclosuresFromListing(String sourceUrl, String raw, List<String> domains) {
        if (!matchesDomain(sourceUrl, domains) || raw == null) return List.of();
        List<NewsItem> items = new ArrayList<>();
        Pattern entry = Pattern.compile("(?m)^\\s*(?:[-*]\\s*)?(?:(\\d{4}-\\d{2}-\\d{2})\\s*)?\\[([^\\]\\n]+)]\\(([^)\\s]+)(?:[ \\t]+\"[^\"\\n]*\")?\\)\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*$");
        var matcher = entry.matcher(raw);
        while (matcher.find()) {
            String date = matcher.group(1) != null ? matcher.group(1) : matcher.group(4);
            String title = matcher.group(2);
            var labelDate = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\s*|\\s*(\\d{4}-\\d{2}-\\d{2})$").matcher(title);
            if (date == null && labelDate.find()) {
                date = labelDate.group(1) != null ? labelDate.group(1) : labelDate.group(2);
                title = labelDate.replaceFirst("").trim();
            }
            if (date == null) continue;
            try {
                String link = URI.create(sourceUrl).resolve(matcher.group(3)).toString();
                if (!matchesDomain(link, domains) || !isNewsUrl(link)) continue;
                items.add(new NewsItem(title, "官方披露目录列出《" + title
                        + "》。这里只核实文件链接与披露日期，未提取该财报全文或指标。目录来源：" + sourceUrl,
                        link, URI.create(sourceUrl).getHost(), date));
            } catch (RuntimeException ignored) {
                // 损坏链接不进入证据。
            }
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
            List<NewsItem> scored = new ArrayList<>();
            for (NewsItem item : items) {
                String documentText = (item.title() + "\n" + item.summary()).trim();
                if (documentText.isBlank()) {
                    continue;
                }
                Embedding documentEmbedding = embeddingModel.embed(documentText).content();
                double score = cosine(queryEmbedding.vector(), documentEmbedding.vector());
                scored.add(item.withRelevanceScore(score));
                if (score >= relevanceThreshold) {
                    filtered.add(item.withRelevanceScore(score));
                } else {
                    log.debug("过滤低相关度新闻, score: {}, threshold: {}", score, relevanceThreshold);
                }
            }
            // 供应商检索本身已带股票/公司查询；语义模型分数只作排序，不允许因阈值偏高把结果清空。
            if (filtered.isEmpty() && !scored.isEmpty()) {
                filtered.addAll(scored.stream()
                        .sorted(java.util.Comparator.comparing(NewsItem::relevanceScore).reversed())
                        .limit(Math.min(3, scored.size())).toList());
                log.info("新闻语义过滤无达标结果，保留最高分候选, fallbackCount={}", filtered.size());
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

    List<NewsItem> filterByPublishedAt(List<NewsItem> items, LocalDate analysisDate) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Instant exclusiveCutoff = analysisDate.plusDays(1)
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        List<NewsItem> filtered = new ArrayList<>();
        for (NewsItem item : items) {
            Optional<Instant> publishedAt = parsePublishedAt(item.publishedAt());
            if (publishedAt.isEmpty()) {
                filtered.add(item.withTemporalStatus(FinancialFact.TemporalStatus.UNKNOWN));
            } else if (publishedAt.get().isBefore(exclusiveCutoff)) {
                // 统一成 ISO Instant，避免证据构建器把已识别的纯日期再次判成 UNKNOWN。
                filtered.add(new NewsItem(item.title(), item.summary(), item.url(), item.source(),
                        publishedAt.get().toString(), item.relevanceScore(), FinancialFact.TemporalStatus.VERIFIED));
            }
        }
        return List.copyOf(filtered);
    }

    private Optional<Instant> parsePublishedAt(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (RuntimeException ignored) {
            // 继续尝试供应商常见格式。
        }
        try {
            return Optional.of(OffsetDateTime.parse(value).toInstant());
        } catch (RuntimeException ignored) {
            // 继续尝试无时区日期格式。
        }
        try {
            return Optional.of(java.time.ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (RuntimeException ignored) {
            // Tavily news 使用的英文 GMT 时间格式；继续尝试本地时间。
        }
        try {
            return Optional.of(LocalDateTime.parse(value,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toInstant(ZoneOffset.ofHours(8)));
        } catch (RuntimeException ignored) {
            // 继续尝试纯日期。
        }
        try {
            return Optional.of(LocalDate.parse(value).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        } catch (RuntimeException ignored) {
            // SerpAPI organic_results 的英文绝对日期。
        }
        for (String pattern : List.of("MMM d, uuuu", "MMMM d, uuuu", "uuuu年M月d日")) {
            try {
                return Optional.of(LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
                        .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
            } catch (RuntimeException ignored) {
                // 相对时间没有可靠的基准，不猜测发布日期。
            }
        }
        return Optional.empty();
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
    interface NewsSearcher {
        List<NewsItem> search(String query, int maxResults, boolean official) throws Exception;
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
                           Double relevanceScore, FinancialFact.TemporalStatus temporalStatus) {
        public NewsItem(String title, String summary, String url, String source, String publishedAt) {
            this(title, summary, url, source, publishedAt, null, FinancialFact.TemporalStatus.UNKNOWN);
        }

        public NewsItem(String title, String summary, String url, String source, String publishedAt,
                        Double relevanceScore) {
            this(title, summary, url, source, publishedAt, relevanceScore, FinancialFact.TemporalStatus.UNKNOWN);
        }

        private NewsItem withRelevanceScore(double score) {
            return new NewsItem(title, summary, url, source, publishedAt, score, temporalStatus);
        }

        private NewsItem withTemporalStatus(FinancialFact.TemporalStatus status) {
            return new NewsItem(title, summary, url, source, publishedAt, relevanceScore, status);
        }
    }
}
