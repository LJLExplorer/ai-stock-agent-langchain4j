package com.ljl.ai.model.dto;

import com.ljl.ai.research.FinancialFact;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 工作流的技术/财务结果协议；展示文本不再作为数据校验的输入。 */
public record AnalysisToolPayload(String symbol, LocalDate asOf, LocalDate publishedAt,
                                  String sourceName, String sourceUrl,
                                  FinancialFact.TemporalStatus temporalStatus,
                                  Map<String, BigDecimal> metrics) {
    public AnalysisToolPayload {
        metrics = metrics == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
    }
}
