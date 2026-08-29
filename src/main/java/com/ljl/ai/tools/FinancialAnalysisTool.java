package com.ljl.ai.tools;

import com.ljl.ai.client.FinancialDataClient;
import com.ljl.ai.model.dto.ToolResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FinancialAnalysisTool {
    private final FinancialDataClient financialDataClient;

    public FinancialAnalysisTool(FinancialDataClient financialDataClient) {
        this.financialDataClient = financialDataClient;
    }

    @Tool(name = "analyzeFinancialReport", value = "查询并分析上市公司财报，包括营收、净利润、ROE、PE、PB和现金流")
    public ToolResult<String> analyzeFinancialReport(@P("股票代码") String symbol,
                                         @P("报告期，如 2024Q4") String period) {
        log.info("分析财报, symbol: {}, period: {}", symbol, period);
        return ToolResultExecutor.execute("FINANCIAL_DATA_ERROR",
                () -> "财务数据（东方财富数据中心）\n" + financialDataClient.getLatest(symbol, period));
    }
}
