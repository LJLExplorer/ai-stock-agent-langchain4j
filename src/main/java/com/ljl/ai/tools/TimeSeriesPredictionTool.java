package com.ljl.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.model.dto.ToolResult;
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
    public ToolResult<String> predictStockTrend(@P("股票代码；由上游分析流水线决定方法和时间范围，不支持指定模型或预测天数") String symbol) {
        return ToolResultExecutor.executeResult("PREDICTION_ERROR",
                () -> doPredictStockTrend(symbol));
    }

    private ToolResult<String> doPredictStockTrend(String symbol) {
        if (predictionBaseUrl == null || predictionBaseUrl.isBlank()) {
            return ToolResult.failure("PREDICTION_NOT_CONFIGURED", "预测服务未配置（prediction.base-url）");
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
                return ToolResult.failure("PREDICTION_HTTP_ERROR",
                        "daily_stock_analysis 预测调用失败，HTTP " + response.statusCode());
            }

            JSONObject result = JSON.parseObject(response.body());
            JSONObject report = result == null ? null : result.getJSONObject("report");
            JSONObject summary = report == null ? null : report.getJSONObject("summary");
            if (summary == null || summary.getString("trend_prediction") == null
                    || summary.getString("trend_prediction").isBlank()) {
                return ToolResult.failure("PREDICTION_EMPTY_REPORT", "预测服务未返回有效趋势报告");
            }
            JSONObject meta = report == null ? null : report.getJSONObject("meta");
            JSONObject output = new JSONObject();
            output.put("symbol", symbol);
            output.put("source", "daily_stock_analysis");
            output.put("limitation", "上游未提供可验证的预测天数或模型名称");
            output.put("trend_prediction", summary == null ? null : summary.getString("trend_prediction"));
            output.put("current_price", meta == null ? null : meta.getBigDecimal("current_price"));
            output.put("change_pct", meta == null ? null : meta.getBigDecimal("change_pct"));
            output.put("operation_advice", summary == null ? null : summary.getString("operation_advice"));
            JSONObject details = report == null ? null : report.getJSONObject("details");
            JSONObject rawResult = details == null ? null : details.getJSONObject("raw_result");
            output.put("risk_warning", rawResult == null ? null : rawResult.getString("risk_warning"));
            output.put("source_query_id", result.getString("query_id"));
            return ToolResult.success(JSON.toJSONString(output));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("预测服务调用失败, symbol: {}", symbol, e);
            return ToolResult.failure("PREDICTION_ERROR", "预测服务调用失败，请稍后重试");
        }
    }
}
