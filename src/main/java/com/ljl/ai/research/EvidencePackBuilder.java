package com.ljl.ai.research;

import com.ljl.ai.client.NewsSearchClient;
import com.ljl.ai.model.entity.StockQuote;
import com.ljl.ai.model.dto.AnalysisToolPayload;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.workflow.ExecutionTask;
import com.ljl.ai.workflow.TaskStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把确定性工具返回值映射为可追溯事实，并组装不可变证据包。
 */
@Component
public class EvidencePackBuilder {

    private static final Pattern DATA_CUTOFF = Pattern.compile("数据截止日[：:]\\s*(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern REPORT_DATE = Pattern.compile("报告期[：:]\\s*(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern PUBLISHED_DATE = Pattern.compile("披露日期[：:]\\s*(\\d{4}-\\d{2}-\\d{2}|未知)");
    private static final int MODEL_VIEW_BUDGET = 12_000;

    /** 将各类工具数据映射为带单位、来源和时点状态的事实；兼容旧文本结果，并拒绝缺少映射上下文的输入。 */
    public List<FinancialFact> map(StockAnalysisTask task, Object data, AnalysisContext context) {
        if (task == null || data == null || context == null) {
            return List.of();
        }
        if (data instanceof AnalysisToolPayload payload
                && (task == StockAnalysisTask.TECHNICAL_ANALYSIS || task == StockAnalysisTask.FINANCIAL_ANALYSIS)) {
            var type = task == StockAnalysisTask.TECHNICAL_ANALYSIS
                    ? FinancialFact.EvidenceType.TECHNICAL : FinancialFact.EvidenceType.FINANCIAL;
            var temporal = status(task == StockAnalysisTask.FINANCIAL_ANALYSIS
                    ? payload.publishedAt() : payload.asOf(), payload.temporalStatus(), context);
            Instant published = payload.publishedAt() == null ? null
                    : payload.publishedAt().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
            return payload.metrics().entrySet().stream().filter(entry -> entry.getValue() != null)
                    .map(entry -> new FinancialFact(type, entry.getKey(), entry.getValue().toPlainString(),
                            metricUnit(entry.getKey()), metricUnit(entry.getKey()).startsWith("CNY") ? "CNY" : null,
                            payload.asOf() == null ? null : payload.asOf().toString(), payload.asOf(), published,
                            payload.sourceName(), payload.sourceUrl(), Instant.now(), null, null, temporal)).toList();
        }
        return switch (task) {
            case MARKET_DATA -> mapMarket(data, context);
            case TECHNICAL_ANALYSIS -> mapText(FinancialFact.EvidenceType.TECHNICAL,
                    "technical_analysis", data.toString(), extractDate(DATA_CUTOFF, data.toString()),
                    null, "Tencent Finance", quoteUrl(context.symbol()), context);
            case FINANCIAL_ANALYSIS -> mapFinancial(data.toString(), context);
            case NEWS_ANALYSIS -> mapNews(data, context);
        };
    }

    private String metricUnit(String metric) {
        return switch (metric) {
            case "close", "ma5", "ma20" -> "CNY/share";
            case "revenue", "netProfit", "operatingCashFlow" -> "CNY";
            default -> "%";
        };
    }

    /**
     * 仅汇总已完成任务的当前证据，过滤被拒绝及超出分析日期的事实，再按证据 ID 去重和排序。
     * 同时记录缺失与失败项，并生成稳定证据哈希、数据截止时间及供模型使用的事实文本。
     */
    public EvidencePack build(AnalysisContext context, List<ExecutionTask> tasks) {
        Map<String, FinancialFact> unique = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        if (tasks != null) {
            for (ExecutionTask task : tasks) {
                if (task == null) {
                    continue;
                }
                if (task.getStatus() == TaskStatus.FAILED) {
                    missing.add(task.getTaskType().name());
                    failures.add(task.getTaskId() + ": " + value(task.getErrorMessage()));
                }
                List<FinancialFact> evidence = task.getStatus() == TaskStatus.COMPLETED
                        ? task.getCurrentEvidence() : List.of();
                if (task.getStatus() == TaskStatus.COMPLETED && (evidence == null || evidence.isEmpty())) {
                    missing.add(task.getTaskType().name());
                }
                if (evidence != null) {
                    for (FinancialFact fact : evidence) {
                        if (isUsable(fact, context)) {
                            unique.putIfAbsent(fact.evidenceId(), fact);
                        }
                    }
                }
            }
        }

        List<FinancialFact> facts = unique.values().stream()
                .sorted(Comparator.comparing(FinancialFact::evidenceId))
                .toList();
        facts.stream()
                .filter(fact -> fact.temporalStatus() == FinancialFact.TemporalStatus.UNKNOWN)
                .forEach(fact -> missing.add("时间未知: " + fact.evidenceId()));
        List<String> sortedMissing = missing.stream().distinct().sorted().toList();
        List<String> sortedFailures = failures.stream().distinct().sorted().toList();

        EnumMap<FinancialFact.EvidenceType, List<FinancialFact>> byType =
                new EnumMap<>(FinancialFact.EvidenceType.class);
        for (FinancialFact.EvidenceType type : FinancialFact.EvidenceType.values()) {
            List<FinancialFact> typed = facts.stream().filter(fact -> fact.evidenceType() == type).toList();
            if (!typed.isEmpty()) {
                byType.put(type, typed);
            }
        }
        Instant dataAsOf = facts.stream()
                .filter(fact -> fact.temporalStatus() == FinancialFact.TemporalStatus.VERIFIED)
                .map(this::factTimestamp).filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        String evidenceHash = hash(facts.stream().map(FinancialFact::evidenceId).toList());
        return new EvidencePack(context, byType, sortedMissing, sortedFailures, dataAsOf, evidenceHash,
                modelView(facts, sortedMissing));
    }

    private List<FinancialFact> mapMarket(Object data, AnalysisContext context) {
        if (!(data instanceof StockQuote quote)) {
            return List.of();
        }
        LocalDate asOf = quote.getTimestamp() == null ? null : quote.getTimestamp().toLocalDate();
        Instant publishedAt = toInstant(quote.getTimestamp());
        FinancialFact.TemporalStatus status = status(asOf, FinancialFact.TemporalStatus.VERIFIED, context);
        List<FinancialFact> facts = new ArrayList<>();
        addFact(facts, FinancialFact.EvidenceType.MARKET, "price", quote.getPrice(), "CNY/share",
                asOf, publishedAt, "Tencent Finance", "https://gu.qq.com", status);
        addFact(facts, FinancialFact.EvidenceType.MARKET, "changePercent", quote.getChangePercent(), "%",
                asOf, publishedAt, "Tencent Finance", "https://gu.qq.com", status);
        addFact(facts, FinancialFact.EvidenceType.MARKET, "volume", quote.getVolume(), "shares",
                asOf, publishedAt, "Tencent Finance", "https://gu.qq.com", status);
        addFact(facts, FinancialFact.EvidenceType.MARKET, "turnoverRate", quote.getTurnoverRate(), "%",
                asOf, publishedAt, "Tencent Finance", "https://gu.qq.com", status);
        return List.copyOf(facts);
    }

    private List<FinancialFact> mapFinancial(String text, AnalysisContext context) {
        LocalDate reportDate = extractDate(REPORT_DATE, text);
        LocalDate publishedDate = extractDate(PUBLISHED_DATE, text);
        FinancialFact.TemporalStatus supplied = text.contains("时点状态：UNKNOWN")
                ? FinancialFact.TemporalStatus.UNKNOWN : FinancialFact.TemporalStatus.VERIFIED;
        FinancialFact.TemporalStatus status = status(publishedDate, supplied, context);
        Instant publishedAt = publishedDate == null ? null
                : publishedDate.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        Matcher sourceUrl = Pattern.compile("来源链接[：:]\\s*(https?://[^\\s]+)").matcher(text);
        return mapText(FinancialFact.EvidenceType.FINANCIAL, "financial_report", text, reportDate,
                publishedAt, "Eastmoney Data Center", sourceUrl.find() ? sourceUrl.group(1) : null, context, status);
    }

    private List<FinancialFact> mapNews(Object data, AnalysisContext context) {
        if (!(data instanceof Iterable<?> values)) {
            return List.of();
        }
        List<FinancialFact> facts = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof NewsSearchClient.NewsItem item)) {
                continue;
            }
            Instant publishedAt = parseInstant(item.publishedAt());
            LocalDate asOf = publishedAt == null ? null
                    : publishedAt.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
            FinancialFact.TemporalStatus status = status(asOf, item.temporalStatus(), context);
            facts.add(new FinancialFact(FinancialFact.EvidenceType.NEWS,
                    item.title() == null || item.title().isBlank() ? "news" : item.title(),
                    value(item.summary()), null, null, asOf == null ? null : asOf.toString(), asOf,
                    publishedAt, value(item.source()), item.url(), Instant.now(), null, null, status));
        }
        return List.copyOf(facts);
    }

    private List<FinancialFact> mapText(FinancialFact.EvidenceType type, String metric, String text,
                                        LocalDate asOf, Instant publishedAt, String source, String url,
                                        AnalysisContext context) {
        return mapText(type, metric, text, asOf, publishedAt, source, url, context,
                status(asOf, FinancialFact.TemporalStatus.VERIFIED, context));
    }

    private List<FinancialFact> mapText(FinancialFact.EvidenceType type, String metric, String text,
                                        LocalDate asOf, Instant publishedAt, String source, String url,
                                        AnalysisContext context, FinancialFact.TemporalStatus status) {
        return List.of(new FinancialFact(type, metric, text, null, null,
                asOf == null ? null : asOf.toString(), asOf, publishedAt, source, url,
                Instant.now(), null, null, status));
    }

    private void addFact(List<FinancialFact> facts, FinancialFact.EvidenceType type, String metric,
                         Object rawValue, String unit, LocalDate asOf, Instant publishedAt,
                         String source, String url, FinancialFact.TemporalStatus status) {
        if (rawValue != null) {
            facts.add(new FinancialFact(type, metric, rawValue.toString(), unit,
                    unit != null && unit.startsWith("CNY") ? "CNY" : null,
                    asOf == null ? null : asOf.toString(), asOf, publishedAt, source, url,
                    Instant.now(), null, null, status));
        }
    }

    private boolean isUsable(FinancialFact fact, AnalysisContext context) {
        return fact != null
                && fact.temporalStatus() != FinancialFact.TemporalStatus.REJECTED
                && (fact.asOf() == null || !fact.asOf().isAfter(context.analysisDate()));
    }

    /** 将数据自带状态与统一分析日期合并判断：保留拒绝状态，时间缺失标记未知，未来数据标记拒绝。 */
    private FinancialFact.TemporalStatus status(LocalDate factDate, FinancialFact.TemporalStatus supplied,
                                                AnalysisContext context) {
        if (supplied == FinancialFact.TemporalStatus.REJECTED) {
            return supplied;
        }
        if (factDate == null || supplied == FinancialFact.TemporalStatus.UNKNOWN) {
            return FinancialFact.TemporalStatus.UNKNOWN;
        }
        return factDate.isAfter(context.analysisDate())
                ? FinancialFact.TemporalStatus.REJECTED : FinancialFact.TemporalStatus.VERIFIED;
    }

    private LocalDate extractDate(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(value(text));
        if (!matcher.find() || "未知".equals(matcher.group(1))) {
            return null;
        }
        return LocalDate.parse(matcher.group(1));
    }

    private Instant parseInstant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    }

    private String quoteUrl(String symbol) {
        String normalized = value(symbol).trim().toUpperCase(java.util.Locale.ROOT);
        String code = normalized.replaceAll("[^0-9]", "");
        if (code.length() != 6) {
            return "https://gu.qq.com";
        }
        String market = normalized.endsWith(".SZ") ? "sz"
                : normalized.endsWith(".BJ") ? "bj" : "sh";
        return "https://gu.qq.com/" + market + code + "/gp";
    }

    private Instant factTimestamp(FinancialFact fact) {
        if (fact.publishedAt() != null) {
            return fact.publishedAt();
        }
        return fact.asOf() == null ? null : fact.asOf().atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /** 只把时间已核实的事实写入模型上下文，按整条事实控制正文预算，并附上缺失项。 */
    private String modelView(List<FinancialFact> facts, List<String> missing) {
        StringBuilder view = new StringBuilder();
        for (FinancialFact fact : facts) {
            // 未核实时间的材料仅保留为待核实来源，不进入模型事实上下文。
            if (fact.temporalStatus() != FinancialFact.TemporalStatus.VERIFIED) {
                continue;
            }
            String line = "[" + fact.evidenceId() + "] " + fact.evidenceType() + "/" + fact.metric()
                    + "=" + fact.value() + " | asOf=" + value(fact.asOf())
                    + " | status=" + fact.temporalStatus() + " | source=" + value(fact.sourceName()) + "\n";
            if (view.length() + line.length() > MODEL_VIEW_BUDGET) {
                break;
            }
            view.append(line);
        }
        if (!missing.isEmpty()) {
            view.append("缺失项：").append(String.join("；", missing));
        }
        return view.toString().strip();
    }

    private String hash(List<String> evidenceIds) {
        try {
            String canonical = String.join("\n", evidenceIds);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private String value(Object value) {
        return value == null ? "未知" : value.toString();
    }
}
