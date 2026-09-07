package com.ljl.ai.tools;

import com.ljl.ai.client.FinancialDataClient;
import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.model.dto.AnalysisToolPayload;
import com.ljl.ai.research.AnalysisContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;

@Slf4j
@Component
public class FinancialAnalysisTool {
    private final FinancialDataClient financialDataClient;

    public FinancialAnalysisTool(FinancialDataClient financialDataClient) {
        this.financialDataClient = financialDataClient;
    }

    @Tool(name = "analyzeFinancialReport", value = "查询上市公司财报中的营收、净利润、增长率、ROE和经营现金流")
    public ToolResult<String> analyzeFinancialReport(@P("股票代码") String symbol,
                                         @P("报告期，如 2024Q4") String period) {
        log.info("分析财报, symbol: {}, period: {}", symbol, period);
        return ToolResultExecutor.execute("FINANCIAL_DATA_ERROR",
                () -> "财务数据（东方财富数据中心）\n" + financialDataClient.getLatest(symbol, period));
    }

    /** 工作流专用入口，按披露日期约束可见财务数据。 */
    public ToolResult<String> analyzeFinancialReport(String symbol, String period, AnalysisContext context) {
        if (context == null) {
            throw new IllegalArgumentException("AnalysisContext 不能为空");
        }
        log.info("分析历史财报, symbol: {}, period: {}, analysisDate: {}",
                symbol, period, context.analysisDate());
        return ToolResultExecutor.execute("FINANCIAL_DATA_ERROR", () -> {
            FinancialDataClient.FinancialSnapshot snapshot =
                    financialDataClient.getLatest(symbol, period, context.analysisDate());
            return "财务数据（东方财富数据中心）"
                    + "\n请求期间：" + period
                    + "\n报告期：" + value(snapshot.reportDate())
                    + "\n披露日期：" + value(snapshot.publishedAt())
                    + "\n时点状态：" + snapshot.temporalStatus()
                    + "\n来源：" + snapshot.values().getOrDefault("source", "未知")
                    + "\n来源链接：" + snapshot.values().getOrDefault("sourceUrl", "未知")
                    + "\n指标：" + snapshot.values();
        });
    }

    private String value(Object value) {
        return value == null ? "未知" : value.toString();
    }

    /**
     * 读取分析日当时可见的财务快照，将协议内指标转换为十进制数，并保留报告期、披露日及真实来源。
     * 缺失指标保持缺失，交由工作流统一校验，避免用默认零值掩盖数据不足。
     */
    public ToolResult<AnalysisToolPayload> financialSnapshot(String symbol, String period, AnalysisContext context) {
        return ToolResultExecutor.execute("FINANCIAL_DATA_ERROR", () -> {
            var snapshot = financialDataClient.getLatest(symbol, period, context.analysisDate());
            var metrics = new LinkedHashMap<String, BigDecimal>();
            for (String metric : List.of("revenue", "netProfit", "revenueGrowth",
                    "netProfitGrowth", "roe", "operatingCashFlow")) {
                Object raw = snapshot.values().get(metric);
                if (raw != null) metrics.put(metric, new BigDecimal(raw.toString()));
            }
            return new AnalysisToolPayload((String) snapshot.values().get("symbol"), snapshot.reportDate(),
                    snapshot.publishedAt(), (String) snapshot.values().get("source"),
                    (String) snapshot.values().get("sourceUrl"), snapshot.temporalStatus(), metrics);
        });
    }
}
