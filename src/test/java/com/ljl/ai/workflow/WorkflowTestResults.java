package com.ljl.ai.workflow;

import com.alibaba.fastjson2.JSON;
import com.ljl.ai.client.NewsSearchClient;
import com.ljl.ai.model.dto.AnalysisToolPayload;
import com.ljl.ai.model.entity.StockQuote;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.research.EvidencePackBuilder;
import com.ljl.ai.research.FinancialFact;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** 图路由测试也使用真实工具协议，避免任意文本被当作有效行情。 */
final class WorkflowTestResults {
    static void complete(ExecutionTask task) {
        complete(task, LocalDate.now(ZoneId.of("Asia/Shanghai")));
    }

    static void complete(ExecutionTask task, LocalDate date) {
        AnalysisContext context = new AnalysisContext("600519.SH", date, null, null, null, null, null);
        Object data = switch (task.getTaskType()) {
            case MARKET_DATA -> StockQuote.builder().symbol("600519.SH").price(new BigDecimal("1500"))
                    .timestamp(date.atTime(15, 0)).build();
            case NEWS_ANALYSIS -> List.of(new NewsSearchClient.NewsItem("异常波动公告",
                    "公司回应项目失败的报道，无数据查询错误", "https://example.test/news", "交易所",
                    date.atTime(8, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toString(),
                    0.8, FinancialFact.TemporalStatus.VERIFIED));
            case TECHNICAL_ANALYSIS -> new AnalysisToolPayload("600519.SH", date, null,
                    "Tencent Finance", "https://gu.qq.com/sh600519/gp", FinancialFact.TemporalStatus.VERIFIED,
                    Map.of("close", new BigDecimal("1500"), "ma5", new BigDecimal("1490"),
                            "ma20", new BigDecimal("1480"), "changePercent", new BigDecimal("-1")));
            case FINANCIAL_ANALYSIS -> new AnalysisToolPayload("600519.SH", date.minusDays(90), date.minusDays(30),
                    "Eastmoney", "https://example.test/report", FinancialFact.TemporalStatus.VERIFIED,
                    Map.of("revenue", new BigDecimal("1500"), "netProfit", new BigDecimal("-100"),
                            "revenueGrowth", new BigDecimal("-2"), "netProfitGrowth", new BigDecimal("-150"),
                            "roe", new BigDecimal("-5"), "operatingCashFlow", new BigDecimal("-100")));
        };
        task.complete(JSON.toJSONString(data), new EvidencePackBuilder().map(task.getTaskType(), data, context));
    }
}
