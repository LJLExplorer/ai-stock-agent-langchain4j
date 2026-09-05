package com.ljl.ai.research;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.agent.DeepResearchAssistant;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 固定顺序、固定调用上限的深度研究编排器。 */
@Slf4j
public final class DeepResearchService {
    private static final int EVIDENCE_BUDGET = 12_000;
    private static final int ROLE_OUTPUT_BUDGET = 2_000;
    private static final int UPSTREAM_BUDGET = 10_000;

    private final DeepResearchAssistant assistant;

    public DeepResearchService(DeepResearchAssistant assistant) {
        this.assistant = assistant;
    }

    public ResearchConclusion research(EvidencePack evidencePack) {
        if (evidencePack == null) {
            throw new IllegalArgumentException("EvidencePack 不能为空");
        }
        String evidence = truncate(evidencePack.modelView(), EVIDENCE_BUDGET);
        List<RoleResult> results = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        for (Role role : Role.values()) {
            String upstream = upstream(results);
            try {
                String output = invoke(role, evidence, upstream);
                results.add(new RoleResult(role, truncate(value(output), ROLE_OUTPUT_BUDGET)));
            } catch (RuntimeException exception) {
                String limitation = "ROLE_FAILED:" + role.name();
                limitations.add(limitation);
                results.add(new RoleResult(role, limitation));
                log.warn("deep_research_role_failed role={}, errorType={}",
                        role, exception.getClass().getSimpleName());
            }
        }

        LocalDate cutoff = dataAsOf(evidencePack);
        try {
            String rawJudge = assistant.judge(evidence, upstream(results));
            ResearchConclusion judged = parseJudge(rawJudge, evidencePack, cutoff);
            if (limitations.isEmpty()) {
                return judged;
            }
            return new ResearchConclusion(judged.rating(), judged.confidence(), judged.summary(),
                    judged.evidenceIds(), judged.risks(), judged.dataAsOf(), true, limitations);
        } catch (RuntimeException exception) {
            String reason = exception instanceof JudgeValidationException validation
                    ? validation.code : "JUDGE_FAILED";
            limitations.add(reason);
            log.warn("deep_research_judge_failed errorType={}, reason={}",
                    exception.getClass().getSimpleName(), reason);
            return fallback(cutoff, limitations);
        }
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
        JSONObject json = JSON.parseObject(extractJson(raw));
        ResearchConclusion.Rating rating = ResearchConclusion.Rating.valueOf(
                value(json.getString("rating")).toUpperCase(Locale.ROOT));
        Double confidence = json.getDouble("confidence");
        LocalDate conclusionDate = LocalDate.parse(value(json.getString("dataAsOf")));
        if (conclusionDate.isAfter(cutoff)) {
            throw new JudgeValidationException("DATE_AFTER_DATA_AS_OF");
        }
        List<String> evidenceIds = stringList(json, "evidenceIds");
        Set<String> available = availableEvidenceIds(pack);
        List<String> unknown = evidenceIds.stream().filter(id -> !available.contains(id)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new JudgeValidationException("UNKNOWN_EVIDENCE_ID:" + String.join(",", unknown));
        }
        return new ResearchConclusion(rating, confidence == null ? Double.NaN : confidence,
                json.getString("summary"), evidenceIds, stringList(json, "risks"),
                conclusionDate, false, List.of());
    }

    private Set<String> availableEvidenceIds(EvidencePack pack) {
        Set<String> ids = new LinkedHashSet<>();
        pack.evidenceByType().values().forEach(facts -> facts.stream()
                .filter(fact -> fact != null
                        && fact.temporalStatus() != FinancialFact.TemporalStatus.REJECTED)
                .map(FinancialFact::evidenceId).forEach(ids::add));
        return ids;
    }

    private List<String> stringList(JSONObject json, String key) {
        List<String> values = json.getList(key, String.class);
        return values == null ? List.of() : List.copyOf(values);
    }

    private String extractJson(String raw) {
        String value = value(raw);
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new JudgeValidationException("JUDGE_FAILED");
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

    private static final class JudgeValidationException extends IllegalArgumentException {
        private final String code;

        private JudgeValidationException(String code) {
            super(code);
            this.code = code;
        }
    }
}
