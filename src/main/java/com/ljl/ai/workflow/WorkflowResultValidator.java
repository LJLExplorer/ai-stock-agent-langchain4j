package com.ljl.ai.workflow;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.research.FinancialFact;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 校验已持久化的工具成功快照，因此首次执行和断点恢复走同一组规则。 */
public class WorkflowResultValidator {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    // 日历日容差，非交易所交易日历；显式策略，不能把 VERIFIED 当作新鲜度证明。
    private static final Map<StockAnalysisTask, Long> MAX_AGE_DAYS = Map.of(
            StockAnalysisTask.MARKET_DATA, 10L, StockAnalysisTask.TECHNICAL_ANALYSIS, 10L,
            StockAnalysisTask.FINANCIAL_ANALYSIS, 550L, StockAnalysisTask.NEWS_ANALYSIS, 30L);
    private final Clock clock;

    public WorkflowResultValidator() {
        this(Clock.system(MARKET_ZONE));
    }

    public WorkflowResultValidator(Clock clock) {
        this.clock = clock;
    }

    /**
     * 对成功任务的持久化结果执行确定性校验：检查协议、标的、时点、新鲜度及证据与原始快照的一致性。
     * 首次执行和恢复后的任务共用此入口；返回空列表表示通过，否则保留任务及字段级失败原因。
     */
    public List<ValidationIssue> validate(ExecutionState state, ExecutionTask task) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (task.getTaskType() == null) {
            return List.of(issue(task, "SCHEMA_INVALID", "taskType", "任务类型为空"));
        }
        Object payload;
        try {
            payload = JSON.parse(task.getResult());
        } catch (RuntimeException exception) {
            return List.of(issue(task, "SCHEMA_INVALID", "result", "结果不是合法 JSON"));
        }
        if (payload == null) {
            return List.of(issue(task, "SCHEMA_INVALID", "result", "结果为空"));
        }
        String expected = state.getAnalysisContext() == null
                ? state.getPlan() == null ? null : state.getPlan().getSymbol()
                : state.getAnalysisContext().symbol();
        if (state.getPlan() != null && state.getAnalysisContext() != null
                && !sameSymbol(state.getPlan().getSymbol(), expected)) {
            issues.add(issue(task, "SYMBOL_MISMATCH", "context.symbol", "计划与分析上下文股票不一致"));
        }
        LocalDate cutoff = state.getAnalysisContext() == null
                ? state.getCreatedAt() == null ? LocalDate.now(clock) : state.getCreatedAt().toLocalDate()
                : state.getAnalysisContext().analysisDate();
        if (cutoff == null) {
            return List.of(issue(task, "SCHEMA_INVALID", "analysisDate", "分析日期为空"));
        }
        LocalDate payloadDate = validateSchema(task, payload, expected, issues);
        // Schema 失败时不继续推断证据，避免错误类型在下游被强制转换。
        if (!issues.isEmpty()) return List.copyOf(issues);

        List<FinancialFact> facts = task.getCurrentEvidence();
        Set<String> validIds = new HashSet<>();
        Set<String> metrics = new HashSet<>();
        Map<String, FinancialFact> unique = new HashMap<>();
        if (facts != null) {
            for (FinancialFact fact : facts) {
                int before = issues.size();
                if (fact == null) {
                    issues.add(issue(task, "EVIDENCE_REQUIRED", "evidence", "证据项为空"));
                    continue;
                }
                String path = "evidence[" + fact.evidenceId() + "]";
                FinancialFact previous = unique.putIfAbsent(fact.evidenceId(), fact);
                if (previous != null && !previous.equals(fact)) {
                    issues.add(issue(task, "EVIDENCE_ID_CONFLICT", path, "同一证据 ID 对应不同事实"));
                }
                if (fact.evidenceType() != evidenceType(task.getTaskType())) {
                    issues.add(issue(task, "EVIDENCE_TYPE_MISMATCH", path, "证据类型与任务不一致"));
                }
                if (!text(fact.evidenceId()) || !text(fact.metric()) || !text(fact.value())
                        || fact.retrievedAt() == null) {
                    issues.add(issue(task, "REQUIRED_FIELD_MISSING", path, "证据必填字段缺失"));
                }
                if (!text(fact.sourceName()) || "未知".equals(fact.sourceName()) || !httpUrl(fact.sourceUrl())) {
                    issues.add(issue(task, "SOURCE_MISSING", path + ".source", "缺少来源名称或有效 HTTP(S) 链接"));
                }
                if (fact.temporalStatus() != FinancialFact.TemporalStatus.VERIFIED || fact.asOf() == null) {
                    issues.add(issue(task, "TIME_UNVERIFIED", path + ".asOf", "证据时间未核实"));
                } else {
                    if (fact.asOf().isAfter(cutoff) || fact.publishedAt() != null
                            && fact.publishedAt().atZone(MARKET_ZONE).toLocalDate().isAfter(cutoff)) {
                        issues.add(issue(task, "FUTURE_EVIDENCE", path + ".asOf", "证据在分析截止日之后才可见"));
                    }
                    if (fact.asOf().isBefore(cutoff.minusDays(MAX_AGE_DAYS.get(task.getTaskType())))) {
                        issues.add(issue(task, "STALE_EVIDENCE", path + ".asOf", "证据超过任务允许的日历日龄"));
                    }
                    if (payloadDate != null && !payloadDate.equals(fact.asOf())) {
                        issues.add(issue(task, "EVIDENCE_MISMATCH", path + ".asOf", "证据日期与工具快照不一致"));
                    }
                }
                if ((task.getTaskType() == StockAnalysisTask.FINANCIAL_ANALYSIS
                        || task.getTaskType() == StockAnalysisTask.NEWS_ANALYSIS) && fact.publishedAt() == null) {
                    issues.add(issue(task, "TIME_UNVERIFIED", path + ".publishedAt", "缺少披露或发布时间"));
                }
                if (task.getTaskType() == StockAnalysisTask.NEWS_ANALYSIS && fact.publishedAt() != null
                        && !fact.publishedAt().atZone(MARKET_ZONE).toLocalDate().equals(fact.asOf())) {
                    issues.add(issue(task, "EVIDENCE_MISMATCH", path + ".asOf", "新闻日期与发布时间不一致"));
                }
                if (payload instanceof JSONObject object) {
                    if (!allowedMetrics(task.getTaskType()).contains(fact.metric())) {
                        issues.add(issue(task, "SCHEMA_INVALID", path + ".metric", "该任务协议未定义此证据指标"));
                    }
                    JSONObject values = task.getTaskType() == StockAnalysisTask.MARKET_DATA
                            ? object : object.getJSONObject("metrics");
                    Object raw = values.get(fact.metric());
                    BigDecimal value = decimal(fact.value());
                    String unit = expectedUnit(fact.metric());
                    if (value == null || unit == null || !legalNumber(fact.metric(), value) || !Objects.equals(unit, fact.unit())
                            || !Objects.equals(unit != null && unit.startsWith("CNY") ? "CNY" : null, fact.currency())) {
                        issues.add(issue(task, "NUMERIC_INVALID", path + ".value", "数值格式、范围或单位不合法"));
                    }
                    if (raw == null || decimal(raw) == null || value == null || decimal(raw).compareTo(value) != 0) {
                        issues.add(issue(task, "EVIDENCE_MISMATCH", path + ".value", "证据数值与工具快照不一致"));
                    }
                    if (task.getTaskType() != StockAnalysisTask.MARKET_DATA
                            && (!Objects.equals(fact.sourceName(), object.getString("sourceName"))
                            || !Objects.equals(fact.sourceUrl(), object.getString("sourceUrl"))
                            || !Objects.equals(fact.publishedAt() == null ? null
                            : fact.publishedAt().atZone(MARKET_ZONE).toLocalDate().toString(), object.getString("publishedAt")))) {
                        issues.add(issue(task, "EVIDENCE_MISMATCH", path + ".source", "来源或披露日期与工具快照不一致"));
                    }
                } else if (payload instanceof JSONArray news && news.stream().noneMatch(item ->
                        item instanceof JSONObject article && Objects.equals(fact.metric(), article.getString("title"))
                                && Objects.equals(fact.value(), article.getString("summary"))
                                && Objects.equals(fact.sourceUrl(), article.getString("url"))
                                && Objects.equals(fact.sourceName(), article.getString("source"))
                                && fact.publishedAt() != null
                                && fact.publishedAt().equals(parseInstant(article.getString("publishedAt")))
                                && "VERIFIED".equals(article.getString("temporalStatus")))) {
                    issues.add(issue(task, "EVIDENCE_MISMATCH", path, "新闻证据与本次快照不一致"));
                }
                if (before == issues.size()) {
                    validIds.add(fact.evidenceId());
                    metrics.add(fact.metric());
                }
            }
        }
        // 按不同 evidenceId 计数；行情还必须至少提供价格，成交量不能替代价格。
        int minimum = Math.max(1, requiredMetrics(task.getTaskType()).size());
        if (validIds.size() < minimum) issues.add(issue(task, "INSUFFICIENT_EVIDENCE", "evidence",
                "至少需要 " + minimum + " 条去重后的有效证据"));
        for (String required : requiredMetrics(task.getTaskType())) {
            if (!metrics.contains(required)) {
                issues.add(issue(task, "REQUIRED_METRIC_MISSING", "evidence", "缺少有效指标 " + required));
            }
        }
        return List.copyOf(issues);
    }

    /** 按任务协议检查结果类型和必填字段，并提取用于后续证据比对的日期；格式错误写入问题列表。 */
    private LocalDate validateSchema(ExecutionTask task, Object payload, String expected, List<ValidationIssue> issues) {
        try {
            switch (task.getTaskType()) {
                case MARKET_DATA -> {
                    if (!(payload instanceof JSONObject quote)) throw new IllegalArgumentException("行情必须是对象");
                    symbol(task, expected, stringField(quote, "symbol"), issues);
                    number(task, "price", quote.get("price"), issues);
                    for (String field : List.of("changePercent", "volume", "turnoverRate")) {
                        if (quote.containsKey(field) && quote.get(field) != null) number(task, field, quote.get(field), issues);
                    }
                    return LocalDateTime.parse(stringField(quote, "timestamp").replace(' ', 'T')).toLocalDate();
                }
                case NEWS_ANALYSIS -> {
                    if (!(payload instanceof JSONArray news)) throw new IllegalArgumentException("新闻必须是数组");
                    for (Object item : news) {
                        if (!(item instanceof JSONObject article) || !text(stringField(article, "title"))
                                || !text(stringField(article, "summary")) || !httpUrl(stringField(article, "url"))
                                || !text(stringField(article, "source"))) {
                            throw new IllegalArgumentException("新闻必填字段缺失");
                        }
                    }
                }
                case TECHNICAL_ANALYSIS, FINANCIAL_ANALYSIS -> {
                    if (!(payload instanceof JSONObject data) || !(data.get("metrics") instanceof JSONObject metrics)) {
                        throw new IllegalArgumentException("分析结果及指标必须是对象");
                    }
                    symbol(task, expected, stringField(data, "symbol"), issues);
                    if (!text(stringField(data, "sourceName")) || !httpUrl(stringField(data, "sourceUrl"))) {
                        issues.add(issue(task, "SOURCE_MISSING", "source", "工具结果缺少可追溯来源"));
                    }
                    if (!"VERIFIED".equals(data.getString("temporalStatus"))) {
                        issues.add(issue(task, "TIME_UNVERIFIED", "temporalStatus", "工具结果时间未核实"));
                    }
                    for (String field : requiredMetrics(task.getTaskType())) {
                        number(task, field, metrics.get(field), issues);
                    }
                    for (String field : metrics.keySet()) {
                        if (!requiredMetrics(task.getTaskType()).contains(field)) {
                            issues.add(issue(task, "SCHEMA_INVALID", "metrics." + field, "工具协议未定义该指标"));
                        }
                    }
                    LocalDate asOf = LocalDate.parse(data.getString("asOf"));
                    if (task.getTaskType() == StockAnalysisTask.FINANCIAL_ANALYSIS
                            && LocalDate.parse(data.getString("publishedAt")).isBefore(asOf)) {
                        issues.add(issue(task, "SCHEMA_INVALID", "publishedAt", "披露日期早于报告期"));
                    }
                    return asOf;
                }
            }
        } catch (RuntimeException exception) {
            issues.add(issue(task, "SCHEMA_INVALID", "result", "返回类型或必填字段格式不符合工具协议"));
        }
        return null;
    }

    private void symbol(ExecutionTask task, String expected, String actual, List<ValidationIssue> issues) {
        if (!sameSymbol(expected, actual)) issues.add(issue(task, "SYMBOL_MISMATCH", "symbol", "股票代码缺失或与请求不一致"));
    }

    private boolean sameSymbol(String left, String right) {
        return text(left) && text(right) && normalizeSymbol(left).equals(normalizeSymbol(right));
    }

    private String normalizeSymbol(String symbol) {
        String value = symbol.trim().toUpperCase(Locale.ROOT);
        if (value.matches("(?:SH|SZ|BJ)\\d{6}")) return value.substring(2) + "." + value.substring(0, 2);
        if (value.matches("\\d{6}")) return value + (value.startsWith("6") ? ".SH"
                : value.startsWith("4") || value.startsWith("8") || value.startsWith("92") ? ".BJ" : ".SZ");
        return value;
    }

    private void number(ExecutionTask task, String field, Object raw, List<ValidationIssue> issues) {
        BigDecimal value = decimal(raw);
        if (!(raw instanceof Number) || value == null || !legalNumber(field, value)) {
            issues.add(issue(task, "NUMERIC_INVALID", field, "必填数值缺失、格式或范围不合法"));
        }
    }

    private BigDecimal decimal(Object raw) {
        if (raw == null || !raw.toString().matches("[+-]?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d{1,3})?")) return null;
        try { return new BigDecimal(raw.toString()); } catch (NumberFormatException exception) { return null; }
    }

    private boolean legalNumber(String metric, BigDecimal value) {
        return switch (metric) {
            case "price", "close", "ma5", "ma20" -> value.signum() > 0;
            case "volume" -> value.signum() >= 0 && value.stripTrailingZeros().scale() <= 0;
            case "turnoverRate", "revenue" -> value.signum() >= 0;
            case "changePercent" -> value.compareTo(BigDecimal.valueOf(-100)) >= 0;
            default -> true; // 利润、现金流、ROE和增长率允许负数，不能套用价格约束。
        };
    }

    private static FinancialFact.EvidenceType evidenceType(StockAnalysisTask task) {
        return switch (task) {
            case MARKET_DATA -> FinancialFact.EvidenceType.MARKET;
            case TECHNICAL_ANALYSIS -> FinancialFact.EvidenceType.TECHNICAL;
            case FINANCIAL_ANALYSIS -> FinancialFact.EvidenceType.FINANCIAL;
            case NEWS_ANALYSIS -> FinancialFact.EvidenceType.NEWS;
        };
    }

    private static List<String> requiredMetrics(StockAnalysisTask task) {
        return switch (task) {
            case MARKET_DATA -> List.of("price");
            case TECHNICAL_ANALYSIS -> List.of("close", "changePercent", "ma5", "ma20");
            case FINANCIAL_ANALYSIS -> List.of("revenue", "netProfit", "revenueGrowth", "netProfitGrowth", "roe", "operatingCashFlow");
            case NEWS_ANALYSIS -> List.of();
        };
    }

    private static List<String> allowedMetrics(StockAnalysisTask task) {
        return task == StockAnalysisTask.MARKET_DATA
                ? List.of("price", "changePercent", "volume", "turnoverRate") : requiredMetrics(task);
    }

    private static boolean text(String value) { return value != null && !value.isBlank(); }

    private static Instant parseInstant(String value) {
        try { return Instant.parse(value); } catch (RuntimeException exception) { return null; }
    }

    private static String stringField(JSONObject object, String field) {
        Object value = object.get(field);
        if (value != null && !(value instanceof String)) throw new IllegalArgumentException("字段类型错误: " + field);
        return (String) value;
    }

    private static String expectedUnit(String metric) {
        if (metric == null) return null;
        return switch (metric) {
            case "price", "close", "ma5", "ma20" -> "CNY/share";
            case "revenue", "netProfit", "operatingCashFlow" -> "CNY";
            case "volume" -> "shares";
            case "changePercent", "turnoverRate", "revenueGrowth", "netProfitGrowth", "roe" -> "%";
            default -> null;
        };
    }

    private static boolean httpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && text(uri.getHost());
        } catch (RuntimeException exception) { return false; }
    }

    private static ValidationIssue issue(ExecutionTask task, String code, String field, String message) {
        return new ValidationIssue(task.getTaskId(), code, field, message);
    }

    public record ValidationIssue(String taskId, String code, String field, String message) { }
}
