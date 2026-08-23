package com.ljl.ai.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Component
public class TimeSeriesPredictionTool {
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${prediction.base-url:}")
    private String predictionBaseUrl;

    @Value("${prediction.timeout-seconds:180}")
    private long timeoutSeconds;

    @Tool(name = "predictStockTrend", value = "调用 daily_stock_analysis 的股票分析流水线，返回趋势预测、当前价格、操作建议和风险提示")
    public String predictStockTrend(@P("股票代码") String symbol,
                                    @P("预测未来交易日数量，如 1/3/5/10") int horizon,
                                    @P("模型名称，如 LSTM/Transformer/PatchTST") String model) {
        if (predictionBaseUrl == null || predictionBaseUrl.isBlank()) {
            return "预测服务未配置（prediction.base-url）。请将它设置为 daily_stock_analysis 的地址，"
                    + "例如 http://localhost:8000。请求参数：symbol=" + symbol
                    + ", horizon=" + horizon + ", model=" + model;
        }
        try {
            // daily_stock_analysis 的预测来自完整分析流水线，不是独立的 /predict 模型接口。
            JSONObject payload = new JSONObject();
            payload.put("stock_code", symbol);
            payload.put("report_type", "detailed");
            payload.put("async_mode", false);
            payload.put("notify", false);

            String endpoint = predictionBaseUrl.replaceAll("/+$", "") + "/api/v1/analysis/analyze";
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(payload))).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return "daily_stock_analysis 预测调用失败，HTTP " + response.statusCode()
                        + "：" + response.body();
            }

            JSONObject result = JSON.parseObject(response.body());
            JSONObject report = result.getJSONObject("report");
            JSONObject summary = report == null ? null : report.getJSONObject("summary");
            JSONObject meta = report == null ? null : report.getJSONObject("meta");
            JSONObject output = new JSONObject();
            output.put("symbol", symbol);
            output.put("horizon", horizon);
            output.put("model", "daily_stock_analysis");
            output.put("trend_prediction", summary == null ? null : summary.getString("trend_prediction"));
            output.put("current_price", meta == null ? null : meta.getBigDecimal("current_price"));
            output.put("change_pct", meta == null ? null : meta.getBigDecimal("change_pct"));
            output.put("operation_advice", summary == null ? null : summary.getString("operation_advice"));
            output.put("risk_warning", report == null ? null
                    : report.getJSONObject("details") == null ? null
                    : report.getJSONObject("details").getString("risk_warning"));
            output.put("source_query_id", result.getString("query_id"));
            return JSON.toJSONString(output);
        } catch (Exception e) {
            log.warn("预测服务调用失败, symbol: {}", symbol, e);
            return "预测服务调用失败：" + e.getMessage();
        }
    }
}
