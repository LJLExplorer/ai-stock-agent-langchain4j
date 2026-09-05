package com.ljl.ai.client;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.research.FinancialFact;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PointInTimeDataContractTest {

    @Test
    void dailyBarsAreCutOffAtAnalysisDateBeforeApplyingLimit() {
        List<MarketDataClient.DailyBar> rows = List.of(
                bar("2026-09-01"),
                bar("2026-09-03"),
                bar("2026-09-05")
        );

        List<MarketDataClient.DailyBar> result = MarketDataClient.filterBarsAsOf(
                rows, LocalDate.of(2026, 9, 4), 2);

        assertEquals(List.of("2026-09-01", "2026-09-03"),
                result.stream().map(MarketDataClient.DailyBar::date).toList());
    }

    @Test
    void financialRowsUseDisclosureDateAndRejectFutureKnowledge() {
        JSONArray rows = new JSONArray();
        rows.add(row("2025-12-31", "2026-04-10", "future-publication"));
        rows.add(row("2025-09-30", "2026-03-01", "available"));

        FinancialDataClient.FinancialSnapshot result = FinancialDataClient.selectSnapshotAsOf(
                rows, LocalDate.of(2026, 3, 15), "600519", "2025Q4");

        assertEquals("available", result.values().get("netProfit"));
        assertEquals(LocalDate.of(2026, 3, 1), result.publishedAt());
        assertEquals(FinancialFact.TemporalStatus.VERIFIED, result.temporalStatus());
    }

    @Test
    void missingDisclosureDateIsUnknownInsteadOfInvented() {
        JSONArray rows = new JSONArray();
        JSONObject unknown = row("2025-12-31", null, "unknown-publication");
        rows.add(unknown);

        FinancialDataClient.FinancialSnapshot result = FinancialDataClient.selectSnapshotAsOf(
                rows, LocalDate.of(2026, 3, 15), "600519", "2025Q4");

        assertEquals(null, result.publishedAt());
        assertEquals(FinancialFact.TemporalStatus.UNKNOWN, result.temporalStatus());
    }

    private MarketDataClient.DailyBar bar(String date) {
        return new MarketDataClient.DailyBar(date, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, 1L);
    }

    private JSONObject row(String reportDate, String noticeDate, String netProfit) {
        JSONObject row = new JSONObject();
        row.put("REPORT_DATE", reportDate);
        if (noticeDate != null) {
            row.put("NOTICE_DATE", noticeDate);
        }
        row.put("PARENTNETPROFIT", netProfit);
        return row;
    }
}
