package com.ljl.ai.planner;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Planner 输出的结构化股票分析计划。
 */
@Data
@Builder
@lombok.extern.jackson.Jacksonized
public class AgentPlan {
    private String intent;
    private String symbol;
    private List<StockAnalysisTask> tasks;
}
