package com.ljl.ai.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Slf4j
@Component
public class TimeSeriesPredictionTool {
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${prediction.base-url:}")
    private String predictionBaseUrl;

    @Tool(name = "predictStockTrend", value = "调用 LSTM、Transformer 或 PatchTST 等时序模型预测未来交易日价格趋势")
    public String predictStockTrend(@P("股票代码") String symbol,
                                    @P("预测未来交易日数量，如 1/3/5/10") int horizon,
                                    @P("模型名称，如 LSTM/Transformer/PatchTST") String model) {
        if (predictionBaseUrl == null || predictionBaseUrl.isBlank()) {
            return "预测服务未配置（prediction.base-url），无法生成真实预测。建议配置外部模型服务后重试。"
                    + " 请求参数：symbol=" + symbol + ", horizon=" + horizon + ", model=" + model;
        }
        try {
            String body = String.format("{\"symbol\":\"%s\",\"horizon\":%d,\"model\":\"%s\"}", symbol, horizon, model);
            HttpRequest request = HttpRequest.newBuilder(URI.create(predictionBaseUrl + "/predict"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            log.warn("预测服务调用失败, symbol: {}", symbol, e);
            return "预测服务调用失败：" + e.getMessage();
        }
    }
}
