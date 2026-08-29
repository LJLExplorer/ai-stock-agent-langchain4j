package com.ljl.ai.planner;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 股票分析计划的安全边界：只允许第一版支持的意图、标的和任务进入执行层。
 */
public class PlanValidator {

    private static final String STOCK_ANALYSIS = "STOCK_ANALYSIS";
    private static final Pattern FULL_SYMBOL = Pattern.compile("\\d{6}\\.(SH|SZ|BJ)");
    private static final Pattern RAW_SYMBOL = Pattern.compile("\\d{6}");

    public ValidatedPlan validate(AgentPlan candidate) {
        if (candidate == null) {
            return invalid("规划结果为空");
        }
        if (!STOCK_ANALYSIS.equalsIgnoreCase(StringUtils.trimToEmpty(candidate.getIntent()))) {
            return invalid("不支持的规划意图");
        }
        String symbol = normalizeSymbol(candidate.getSymbol());
        if (symbol == null) {
            return invalid("股票代码缺失或格式非法");
        }
        if (candidate.getTasks() == null || candidate.getTasks().isEmpty()
                || candidate.getTasks().stream().anyMatch(task -> task == null)) {
            return invalid("股票分析任务为空或包含非法任务");
        }

        Set<StockAnalysisTask> uniqueTasks = new LinkedHashSet<>(candidate.getTasks());
        List<StockAnalysisTask> tasks = new ArrayList<>(uniqueTasks);
        List<String> toolNames = tasks.stream().map(StockAnalysisTask::toolName).toList();
        AgentPlan normalized = AgentPlan.builder()
                .intent(STOCK_ANALYSIS)
                .symbol(symbol)
                .tasks(tasks)
                .build();
        return new ValidatedPlan(true, null, normalized, toolNames);
    }

    private String normalizeSymbol(String rawSymbol) {
        if (StringUtils.isBlank(rawSymbol)) {
            return null;
        }
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        if (FULL_SYMBOL.matcher(symbol).matches()) {
            return symbol;
        }
        if (!RAW_SYMBOL.matcher(symbol).matches()) {
            return null;
        }
        if (symbol.startsWith("6") || symbol.startsWith("5") || symbol.startsWith("9")) {
            return symbol + ".SH";
        }
        if (symbol.startsWith("0") || symbol.startsWith("3")) {
            return symbol + ".SZ";
        }
        if (symbol.startsWith("4") || symbol.startsWith("8")) {
            return symbol + ".BJ";
        }
        return null;
    }

    private ValidatedPlan invalid(String message) {
        return new ValidatedPlan(false, message, null, List.of());
    }

    public record ValidatedPlan(boolean valid, String errorMessage, AgentPlan plan, List<String> toolNames) {
    }
}
