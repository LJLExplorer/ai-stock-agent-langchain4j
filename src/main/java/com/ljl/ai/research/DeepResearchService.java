package com.ljl.ai.research;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.agent.DeepResearchAssistant;
import com.ljl.ai.observability.RunEvent;
import com.ljl.ai.observability.RunEventPublisher;
import com.ljl.ai.workflow.AnswerQualityGuard;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/** 按证据范围选择专家，并行生成独立观点后交由单次 Judge 裁决。 */
@Slf4j
public class DeepResearchService {
    private static final int EVIDENCE_BUDGET = 12_000;
    private static final int ROLE_OUTPUT_BUDGET = 2_000;
    private static final int UPSTREAM_BUDGET = 10_000;

    private final DeepResearchAssistant assistant;
    private final RunEventPublisher eventPublisher;
    private final Executor roleExecutor;
    private final AnswerQualityGuard qualityGuard = new AnswerQualityGuard();

    public DeepResearchService(DeepResearchAssistant assistant) {
        this(assistant, null, ForkJoinPool.commonPool());
    }

    public DeepResearchService(DeepResearchAssistant assistant, RunEventPublisher eventPublisher) {
        this(assistant, eventPublisher, ForkJoinPool.commonPool());
    }

    public DeepResearchService(DeepResearchAssistant assistant, RunEventPublisher eventPublisher,
                               Executor roleExecutor) {
        this.assistant = assistant;
        this.eventPublisher = eventPublisher;
        this.roleExecutor = Objects.requireNonNull(roleExecutor, "roleExecutor 不能为空");
    }

    /** 使用同一证据包启动多角色研究，不引入历史决策复盘。 */
    public ResearchConclusion research(EvidencePack evidencePack) {
        if (evidencePack == null) {
            throw new IllegalArgumentException("EvidencePack 不能为空");
        }
        String evidence = researchContext(evidencePack, List.of());
        return research(evidencePack, evidence);
    }

    /** 将本次可见的历史复盘作为校准参考加入上下文，再基于当前证据包完成多角色研究。 */
    public ResearchConclusion research(EvidencePack evidencePack, List<ResearchDecision> decisionReviews) {
        if (evidencePack == null) {
            throw new IllegalArgumentException("EvidencePack 不能为空");
        }
        String evidence = researchContext(evidencePack, decisionReviews);
        return research(evidencePack, evidence);
    }

    /**
     * 并行运行适用角色，按预定顺序收集结果后交给 Judge 裁决，并由 Java 再次校验裁决输出。
     * 无可信证据、所有角色失效或裁决失败时返回证据不足结论；部分角色失败则记录降级限制。
     */
    private ResearchConclusion research(EvidencePack evidencePack, String evidence) {
        if (availableEvidenceIds(evidencePack).isEmpty()) {
            return fallback(dataAsOf(evidencePack), List.of("NO_VERIFIED_EVIDENCE"));
        }
        List<Role> roles = plannedRoles(evidencePack);
        String traceId = evidencePack.context() == null ? MDC.get("traceId") : evidencePack.context().traceId();
        List<CompletableFuture<RoleOutcome>> futures = roles.stream()
                .map(role -> CompletableFuture.supplyAsync(
                        () -> runRole(evidencePack, role, evidence, traceId), roleExecutor))
                .toList();
        List<RoleOutcome> outcomes = futures.stream().map(CompletableFuture::join).toList();
        List<RoleResult> results = outcomes.stream().map(RoleOutcome::result).toList();
        List<String> limitations = new ArrayList<>();
        outcomes.stream().map(RoleOutcome::limitation).filter(Objects::nonNull).forEach(limitations::add);

        LocalDate cutoff = dataAsOf(evidencePack);
        if (outcomes.stream().allMatch(outcome -> outcome.limitation() != null)) {
            limitations.add("NO_VALID_ROLE_OUTPUT");
            return fallback(cutoff, limitations);
        }
        String rawJudge = null;
        publishRoleEvent(evidencePack, RunEvent.EventType.ROLE_STARTED, "JUDGE", "status=started");
        try {
            rawJudge = assistant.judge(evidence, upstream(results));
            ResearchConclusion judged = parseJudge(rawJudge, evidencePack, cutoff);
            publishRoleEvent(evidencePack, RunEvent.EventType.ROLE_COMPLETED, "JUDGE", "status=completed");
            if (limitations.isEmpty()) {
                return judged;
            }
            return new ResearchConclusion(judged.rating(), judged.confidence(), judged.summary(),
                    judged.evidenceIds(), judged.risks(), judged.dataAsOf(), true, limitations);
        } catch (RuntimeException exception) {
            String reason = exception instanceof JudgeValidationException validation
                    ? validation.code : "JUDGE_FAILED";
            limitations.add(reason);
            publishRoleEvent(evidencePack, RunEvent.EventType.ROLE_COMPLETED, "JUDGE",
                    "status=failed;reason=" + reason);
            log.warn("deep_research_judge_failed errorType={}, reason={}, responseLength={}",
                    exception.getClass().getSimpleName(), reason, rawJudge == null ? 0 : rawJudge.length());
            return fallback(cutoff, limitations);
        }
    }

    public int plannedRoleCount(EvidencePack evidencePack) {
        return plannedRoles(evidencePack).size() + 1;
    }

    /** 按证据类型启用专业角色，并固定加入看多、看空与风险角色，避免研究超出已有材料范围。 */
    private List<Role> plannedRoles(EvidencePack evidencePack) {
        Set<FinancialFact.EvidenceType> types = evidencePack == null || evidencePack.evidenceByType() == null
                ? Set.of() : evidencePack.evidenceByType().keySet();
        List<Role> roles = new ArrayList<>();
        if (types.contains(FinancialFact.EvidenceType.FINANCIAL)) {
            roles.add(Role.FUNDAMENTAL);
        }
        if (types.contains(FinancialFact.EvidenceType.TECHNICAL)
                || types.contains(FinancialFact.EvidenceType.MARKET)) {
            roles.add(Role.TECHNICAL);
        }
        if (types.contains(FinancialFact.EvidenceType.NEWS)) {
            roles.add(Role.NEWS);
        }
        roles.add(Role.BULL);
        roles.add(Role.BEAR);
        roles.add(Role.RISK);
        return List.copyOf(roles);
    }

    /** 每个角色仅调用一次模型，检查输出质量与长度，并在并行线程中设置及恢复追踪上下文。 */
    private RoleOutcome runRole(EvidencePack evidencePack, Role role, String evidence, String traceId) {
        String previousTraceId = MDC.get("traceId");
        String previousRole = MDC.get("researchRole");
        if (traceId != null && !traceId.isBlank()) {
            MDC.put("traceId", traceId);
        }
        MDC.put("researchRole", role.name());
        long started = System.nanoTime();
        publishRoleEvent(evidencePack, RunEvent.EventType.ROLE_STARTED, role.name(), "status=started");
        log.info("deep_research_role_started role={}", role);
        try {
            String output = invoke(role, evidence, "");
            AnswerQualityGuard.Validation quality = qualityGuard.validate(output);
            if (!quality.valid() || output.length() > ROLE_OUTPUT_BUDGET) {
                String reason = quality.valid() ? "OUTPUT_TOO_LONG" : quality.reason().name();
                String limitation = "ROLE_INVALID:" + role.name() + ":" + reason;
                publishRoleEvent(evidencePack, RunEvent.EventType.ROLE_COMPLETED, role.name(),
                        "status=failed;reason=" + reason);
                log.warn("deep_research_role_rejected role={}, reason={}, responseLength={}",
                        role, reason, output == null ? 0 : output.length());
                return new RoleOutcome(new RoleResult(role, "该角色输出未通过校验，不作为证据。"), limitation);
            }
            publishRoleEvent(evidencePack, RunEvent.EventType.ROLE_COMPLETED, role.name(), "status=completed");
            log.info("deep_research_role_finished role={}, elapsedMs={}", role, elapsedMillis(started));
            return new RoleOutcome(new RoleResult(role, value(output)), null);
        } catch (RuntimeException exception) {
            String limitation = "ROLE_FAILED:" + role.name();
            publishRoleEvent(evidencePack, RunEvent.EventType.ROLE_COMPLETED, role.name(), "status=failed");
            log.warn("deep_research_role_failed role={}, elapsedMs={}, errorType={}",
                    role, elapsedMillis(started), exception.getClass().getSimpleName());
            return new RoleOutcome(new RoleResult(role, limitation), limitation);
        } finally {
            restoreMdc("traceId", previousTraceId);
            restoreMdc("researchRole", previousRole);
        }
    }

    private long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private void restoreMdc(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private String researchContext(EvidencePack pack, List<ResearchDecision> reviews) {
        String currentEvidence = scopeContext(pack);
        List<ResearchDecision> visible = visibleReviews(pack, reviews);
        if (visible.isEmpty()) {
            return truncate(currentEvidence, EVIDENCE_BUDGET);
        }
        String reviewText = visible.stream()
                .map(decision -> "- 决策日=" + decision.getAnalysisDate()
                        + "；结果可用日=" + decision.getOutcomeAvailableAt()
                        + "；原评级=" + decision.getRating()
                        + "；确定性复盘=" + oneLine(decision.getReflection()))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return truncate("【本轮 EvidencePack（唯一事实依据）】\n" + currentEvidence
                + "\n\n【历史复盘参考（仅用于校准，不得覆盖本轮事实或充当 evidenceId）】\n"
                + reviewText, EVIDENCE_BUDGET);
    }

    private String scopeContext(EvidencePack pack) {
        String evidenceTypes = pack.evidenceByType().keySet().stream()
                .map(Enum::name).sorted().reduce((left, right) -> left + "," + right).orElse("NONE");
        LocalDate analysisDate = pack.context() == null || pack.context().analysisDate() == null
                ? dataAsOf(pack) : pack.context().analysisDate();
        return "【权威分析边界】分析日期=" + analysisDate
                + "；数据截止日=" + dataAsOf(pack)
                + "；本轮证据范围=" + evidenceTypes
                + "；未出现的板块不属于本次分析范围，不得报告为缺失或损坏；"
                + "不晚于分析日期的数据不得称为未来数据。\n"
                + value(pack.modelView());
    }

    private List<ResearchDecision> visibleReviews(EvidencePack pack, List<ResearchDecision> reviews) {
        if (pack.context() == null || reviews == null || reviews.isEmpty()) {
            return List.of();
        }
        AnalysisContext context = pack.context();
        return reviews.stream()
                .filter(Objects::nonNull)
                .filter(decision -> decision.getReviewStatus() == ResearchDecision.ReviewStatus.COMPLETED)
                .filter(decision -> Objects.equals(context.userId(), decision.getUserId()))
                .filter(decision -> Objects.equals(context.symbol(), decision.getSymbol()))
                .filter(decision -> decision.getOutcomeAvailableAt() != null
                        && !decision.getOutcomeAvailableAt().isAfter(context.analysisDate()))
                .sorted(java.util.Comparator.comparing(ResearchDecision::getOutcomeAvailableAt).reversed())
                .limit(5)
                .toList();
    }

    private String oneLine(String content) {
        return value(content).replaceAll("\\s+", " ");
    }

    private String invoke(Role role, String evidence, String upstream) {
        return switch (role) {
            case FUNDAMENTAL -> assistant.fundamental(evidence, upstream);
            case TECHNICAL -> assistant.technical(evidence, upstream);
            case NEWS -> assistant.news(evidence, upstream);
            case BULL -> assistant.bull(evidence, upstream);
            case BEAR -> assistant.bear(evidence, upstream);
            case RISK -> assistant.risk(evidence, upstream);
        };
    }

    private ResearchConclusion parseJudge(String raw, EvidencePack pack, LocalDate cutoff) {
        JSONObject json;
        try {
            json = JSON.parseObject(extractJson(raw));
        } catch (JudgeValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new JudgeValidationException("JUDGE_INVALID_JSON", exception);
        }
        if (json == null) {
            throw new JudgeValidationException("JUDGE_INVALID_JSON");
        }

        ResearchConclusion.Rating rating;
        try {
            rating = ResearchConclusion.Rating.valueOf(
                    value(json.getString("rating")).toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new JudgeValidationException("JUDGE_INVALID_RATING", exception);
        }

        Double confidence;
        try {
            confidence = json.getDouble("confidence");
        } catch (RuntimeException exception) {
            throw new JudgeValidationException("JUDGE_INVALID_CONFIDENCE", exception);
        }
        if (confidence == null || !Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new JudgeValidationException("JUDGE_INVALID_CONFIDENCE");
        }

        String summary = value(json.getString("summary"));
        if (summary.isEmpty()) {
            throw new JudgeValidationException("JUDGE_INVALID_JSON");
        }
        validateProse(summary);

        LocalDate conclusionDate;
        try {
            conclusionDate = LocalDate.parse(value(json.getString("dataAsOf")));
        } catch (RuntimeException exception) {
            throw new JudgeValidationException("JUDGE_INVALID_DATA_AS_OF", exception);
        }
        if (conclusionDate.isAfter(cutoff)) {
            throw new JudgeValidationException("DATE_AFTER_DATA_AS_OF");
        }
        List<String> evidenceIds;
        List<String> risks;
        try {
            evidenceIds = stringList(json, "evidenceIds");
            risks = stringList(json, "risks");
        } catch (RuntimeException exception) {
            throw new JudgeValidationException("JUDGE_INVALID_JSON", exception);
        }
        Set<String> available = availableEvidenceIds(pack);
        List<String> unknown = evidenceIds.stream().filter(id -> !available.contains(id)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new JudgeValidationException("UNKNOWN_EVIDENCE_ID:" + String.join(",", unknown));
        }
        if (rating != ResearchConclusion.Rating.INSUFFICIENT_DATA && evidenceIds.isEmpty()) {
            throw new JudgeValidationException("JUDGE_MISSING_EVIDENCE");
        }
        for (String risk : risks) {
            validateProse(risk);
        }
        return new ResearchConclusion(rating, confidence, summary, evidenceIds, risks,
                conclusionDate, false, List.of());
    }

    private void validateProse(String text) {
        AnswerQualityGuard.Validation quality = qualityGuard.validate(text);
        if (!quality.valid() || text.length() > ROLE_OUTPUT_BUDGET) {
            throw new JudgeValidationException("JUDGE_INVALID_CONTENT:"
                    + (quality.valid() ? "OUTPUT_TOO_LONG" : quality.reason().name()));
        }
    }

    private Set<String> availableEvidenceIds(EvidencePack pack) {
        Set<String> ids = new LinkedHashSet<>();
        pack.evidenceByType().values().forEach(facts -> facts.stream()
                .filter(fact -> fact != null
                        && fact.temporalStatus() == FinancialFact.TemporalStatus.VERIFIED)
                .map(FinancialFact::evidenceId).forEach(ids::add));
        return ids;
    }

    private List<String> stringList(JSONObject json, String key) {
        List<String> values = json.getList(key, String.class);
        return values == null ? List.of() : List.copyOf(values);
    }

    private String extractJson(String raw) {
        String value = value(raw);
        if (value.isEmpty()) {
            throw new JudgeValidationException("JUDGE_EMPTY_RESPONSE");
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new JudgeValidationException("JUDGE_MISSING_JSON_OBJECT");
        }
        return value.substring(start, end + 1);
    }

    private LocalDate dataAsOf(EvidencePack pack) {
        if (pack.dataAsOf() != null) {
            return pack.dataAsOf().atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (pack.context() != null && pack.context().analysisDate() != null) {
            return pack.context().analysisDate();
        }
        throw new IllegalArgumentException("EvidencePack dataAsOf 不能为空");
    }

    private ResearchConclusion fallback(LocalDate cutoff, List<String> limitations) {
        return new ResearchConclusion(ResearchConclusion.Rating.INSUFFICIENT_DATA, 0,
                "裁决结果未通过结构或证据校验，已降级为证据不足。", List.of(),
                List.of("请核验证据完整性后重新执行深度研究。"), cutoff, true,
                limitations.stream().distinct().toList());
    }

    private String upstream(List<RoleResult> results) {
        String content = results.stream()
                .map(result -> "[" + result.role().name() + "]\n" + result.output())
                .reduce((left, right) -> left + "\n\n" + right).orElse("");
        return truncate(content, UPSTREAM_BUDGET);
    }

    private void publishRoleEvent(EvidencePack pack, RunEvent.EventType eventType,
                                  String role, String summary) {
        if (eventPublisher == null || pack.context() == null
                || value(pack.context().executionId()).isEmpty()) {
            return;
        }
        try {
            eventPublisher.publish(pack.context().executionId(), pack.context().traceId(),
                    eventType, role, summary);
        } catch (RuntimeException exception) {
            log.warn("deep_research_progress_publish_failed role={}, errorType={}",
                    role, exception.getClass().getSimpleName());
        }
    }

    private String truncate(String content, int maxLength) {
        String value = value(content);
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    private enum Role {
        FUNDAMENTAL,
        TECHNICAL,
        NEWS,
        BULL,
        BEAR,
        RISK
    }

    private record RoleResult(Role role, String output) {
    }

    private record RoleOutcome(RoleResult result, String limitation) {
    }

    private static final class JudgeValidationException extends IllegalArgumentException {
        private final String code;

        private JudgeValidationException(String code) {
            super(code);
            this.code = code;
        }

        private JudgeValidationException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }
    }
}
