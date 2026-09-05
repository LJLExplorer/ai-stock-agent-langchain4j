package com.ljl.ai.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.model.entity.StockQuote;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Uses the same free Tencent sources as the local daily_stock_analysis project:
 * qt.gtimg.cn for quotes and web.ifzq.gtimg.cn for adjusted daily bars.
 */
@Slf4j
@Component
public class MarketDataClient {
    private static final String QUOTE_URL = "https://qt.gtimg.cn/q=";
    private static final String KLINE_URL = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get";
    private static final DateTimeFormatter QUOTE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    public StockQuote getRealtimeQuote(String rawSymbol) throws Exception {
        String symbol = normalizeSymbol(rawSymbol);
        String body = get(QUOTE_URL + symbol);
        int start = body.indexOf('"');
        int end = body.lastIndexOf('"');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("腾讯行情接口返回空数据: " + rawSymbol);
        }

        String[] fields = body.substring(start + 1, end).split("~", -1);
        if (fields.length < 39 || fields[3].isBlank()) {
            throw new IllegalStateException("腾讯行情接口返回字段不完整: " + rawSymbol);
        }

        return StockQuote.builder()
                .symbol(rawSymbol)
                .name(fields[1])
                .price(decimal(fields, 3))
                .changePercent(decimal(fields, 32))
                .volume(longValue(fields, 6) * 100L)
                .turnoverRate(decimal(fields, 38))
                .timestamp(parseQuoteTime(fields, 30))
                .build();
    }

    public List<DailyBar> getDailyBars(String rawSymbol, int days) throws Exception {
        return getDailyBars(rawSymbol, days, LocalDate.now());
    }

    public List<DailyBar> getDailyBars(String rawSymbol, int days, LocalDate analysisDate) throws Exception {
        if (analysisDate == null) {
            throw new IllegalArgumentException("analysisDate 不能为空");
        }
        if (analysisDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("analysisDate 不能晚于当前日期");
        }
        String symbol = normalizeSymbol(rawSymbol);
        int lookback = analysisDate.isBefore(LocalDate.now())
                ? 800
                : Math.max(30, Math.min(800, days * 2 + 20));
        String url = KLINE_URL + "?param=" + symbol + ",day,,," + lookback + ",qfq";
        JSONObject payload = JSON.parseObject(get(url));
        JSONObject data = payload.getJSONObject("data");
        JSONObject item = data == null ? null : data.getJSONObject(symbol);
        JSONArray rows = item == null ? null : (item.getJSONArray("qfqday") != null
                ? item.getJSONArray("qfqday") : item.getJSONArray("day"));
        if (rows == null || rows.isEmpty()) {
            throw new IllegalStateException("腾讯日K接口返回空数据: " + rawSymbol);
        }

        List<DailyBar> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            JSONArray row = rows.getJSONArray(i);
            if (row == null || row.size() < 6) {
                continue;
            }
            result.add(new DailyBar(
                    row.getString(0), decimal(row, 1), decimal(row, 2),
                    decimal(row, 3), decimal(row, 4), longValue(row, 5) * 100L));
        }
        List<DailyBar> filtered = filterBarsAsOf(result, analysisDate, days);
        if (filtered.isEmpty()) {
            throw new IllegalStateException("分析日期前没有可用日K数据: " + rawSymbol + " " + analysisDate);
        }
        return filtered;
    }

    static List<DailyBar> filterBarsAsOf(List<DailyBar> bars, LocalDate analysisDate, int days) {
        if (bars == null || bars.isEmpty() || days <= 0) {
            return List.of();
        }
        List<DailyBar> eligible = bars.stream()
                .filter(bar -> bar != null && parseBarDate(bar.date()) != null)
                .filter(bar -> !parseBarDate(bar.date()).isAfter(analysisDate))
                .sorted((left, right) -> parseBarDate(left.date()).compareTo(parseBarDate(right.date())))
                .toList();
        int from = Math.max(0, eligible.size() - days);
        return List.copyOf(eligible.subList(from, eligible.size()));
    }

    private static LocalDate parseBarDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String get(String url) throws Exception {
        Request request = new Request.Builder().url(url)
                .header("Referer", "https://finance.qq.com")
                .header("User-Agent", "Mozilla/5.0")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("行情接口 HTTP " + response.code());
            }
            byte[] bytes = response.body().bytes();
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    public static String normalizeSymbol(String rawSymbol) {
        String code = rawSymbol.trim().toLowerCase(Locale.ROOT);
        if (code.matches("(sh|sz|bj)\\d{6}")) return code;
        String plain = code.replaceAll("\\.(sh|sz|bj)$", "");
        if (!plain.matches("\\d{6}")) {
            throw new IllegalArgumentException("当前实时行情适配器仅支持 6 位 A 股代码: " + rawSymbol);
        }
        String market;
        if (plain.startsWith("6") || plain.startsWith("5") || plain.startsWith("9")) {
            market = "sh";
        } else if (plain.startsWith("4") || plain.startsWith("8")) {
            market = "bj";
        } else {
            market = "sz";
        }
        return market + plain;
    }

    private static BigDecimal decimal(String[] values, int index) {
        return index < values.length && !values[index].isBlank() ? new BigDecimal(values[index]) : null;
    }

    private static BigDecimal decimal(JSONArray values, int index) {
        String value = values.getString(index);
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }

    private static long longValue(String[] values, int index) {
        return index < values.length && !values[index].isBlank() ? Long.parseLong(values[index].split("\\.")[0]) : 0L;
    }

    private static long longValue(JSONArray values, int index) {
        String value = values.getString(index);
        return value == null || value.isBlank() ? 0L : Long.parseLong(value.split("\\.")[0]);
    }

    private static LocalDateTime parseQuoteTime(String[] fields, int index) {
        try {
            return LocalDateTime.parse(fields[index], QUOTE_TIME);
        } catch (Exception ignored) {
            return LocalDateTime.now();
        }
    }

    public record DailyBar(String date, BigDecimal open, BigDecimal close, BigDecimal high,
                           BigDecimal low, long volume) {}
}
