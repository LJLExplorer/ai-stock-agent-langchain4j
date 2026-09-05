package com.ljl.ai.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.research.FinancialFact;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Eastmoney data-center adapter, equivalent to the local project's AkShare financial adapter. */
@Slf4j
@Component
public class FinancialDataClient {
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build();

    public Map<String, Object> getLatest(String rawSymbol, String period) throws Exception {
        return getLatest(rawSymbol, period, LocalDate.now()).values();
    }

    public FinancialSnapshot getLatest(String rawSymbol, String period, LocalDate analysisDate) throws Exception {
        if (analysisDate == null) {
            throw new IllegalArgumentException("analysisDate 不能为空");
        }
        if (analysisDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("analysisDate 不能晚于当前日期");
        }
        String code = rawSymbol.trim().toUpperCase();
        String secucode = code.matches("\\d{6}\\.(SH|SZ|BJ)") ? code : code + (code.startsWith("6") ? ".SH" : ".SZ");
        String filter = URLEncoder.encode("(SECUCODE=\"" + secucode + "\")", StandardCharsets.UTF_8);
        // Eastmoney retired RPT_F10_FINANCE_MAIN (now returns code 9501 "报表配置不存在").
        // RPT_F10_FINANCE_MAINFINADATA is its replacement, with renamed fields.
        String url = "https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_F10_FINANCE_MAINFINADATA"
                + "&columns=ALL&filter=" + filter + "&pageNumber=1&pageSize=50&sortColumns=REPORT_DATE&sortTypes=-1";
        Request request = new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("东方财富财务接口 HTTP " + response.code());
            JSONObject root = JSON.parseObject(response.body().string());
            JSONArray data = root.getJSONArray("result") == null ? null : root.getJSONObject("result").getJSONArray("data");
            if (data == null || data.isEmpty()) throw new IllegalStateException("未找到财务数据: " + rawSymbol + " " + period);
            FinancialSnapshot snapshot = selectSnapshotAsOf(data, analysisDate, rawSymbol, period);
            if (snapshot == null) {
                throw new IllegalStateException("分析日期前没有可用财务数据: " + rawSymbol + " " + analysisDate);
            }
            return snapshot;
        }
    }

    static FinancialSnapshot selectSnapshotAsOf(JSONArray rows, LocalDate analysisDate,
                                                String rawSymbol, String period) {
        JSONObject unknownCandidate = null;
        if (rows == null) {
            return null;
        }
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row == null) {
                continue;
            }
            LocalDate reportDate = parseDate(first(row, "REPORT_DATE", "REPORT_DATE_NAME"));
            if (reportDate != null && reportDate.isAfter(analysisDate)) {
                continue;
            }
            LocalDate publishedAt = parseDate(first(row, "NOTICE_DATE", "ANNOUNCE_DATE", "UPDATE_DATE"));
            if (publishedAt == null) {
                if (unknownCandidate == null) {
                    unknownCandidate = row;
                }
                continue;
            }
            if (!publishedAt.isAfter(analysisDate)) {
                return snapshot(row, rawSymbol, period, reportDate, publishedAt,
                        FinancialFact.TemporalStatus.VERIFIED);
            }
        }
        if (unknownCandidate == null) {
            return null;
        }
        return snapshot(unknownCandidate, rawSymbol, period,
                parseDate(first(unknownCandidate, "REPORT_DATE", "REPORT_DATE_NAME")), null,
                FinancialFact.TemporalStatus.UNKNOWN);
    }

    private static FinancialSnapshot snapshot(JSONObject row, String rawSymbol, String period,
                                              LocalDate reportDate, LocalDate publishedAt,
                                              FinancialFact.TemporalStatus temporalStatus) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", rawSymbol);
        result.put("requestedPeriod", period);
        result.put("reportDate", reportDate);
        result.put("publishedAt", publishedAt);
        result.put("temporalStatus", temporalStatus);
        result.put("revenue", first(row, "TOTALOPERATEREVE"));
        result.put("netProfit", first(row, "PARENTNETPROFIT"));
        result.put("revenueGrowth", first(row, "TOTALOPERATEREVETZ"));
        result.put("netProfitGrowth", first(row, "PARENTNETPROFITTZ"));
        result.put("roe", first(row, "ROEJQ"));
        result.put("operatingCashFlow", first(row, "NETCASH_OPERATE_PK"));
        result.put("source", "Eastmoney Data Center / AkShare-equivalent");
        return new FinancialSnapshot(result, reportDate, publishedAt, temporalStatus);
    }

    private static LocalDate parseDate(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        String value = rawValue.toString().trim();
        if (value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Object first(JSONObject row, String... keys) {
        for (String key : keys) if (row.containsKey(key) && row.get(key) != null) return row.get(key);
        return null;
    }

    public record FinancialSnapshot(Map<String, Object> values, LocalDate reportDate,
                                    LocalDate publishedAt, FinancialFact.TemporalStatus temporalStatus) {
        public FinancialSnapshot {
            values = values == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }
}
