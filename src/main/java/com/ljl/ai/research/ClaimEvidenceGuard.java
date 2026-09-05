package com.ljl.ai.research;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 对回答中的显式证据引用、数值和日期执行确定性校验。 */
@Component
public final class ClaimEvidenceGuard {
    private static final Pattern CITATION = Pattern.compile("\\[evidence:(ev-[A-Za-z0-9._-]+)]");
    private static final Pattern DATE = Pattern.compile("(?<!\\d)(20\\d{2}-\\d{2}-\\d{2})(?!\\d)");
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z0-9_.-])[-+]?\\d+(?:\\.\\d+)?%?");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern ORDERED_LIST_PREFIX = Pattern.compile("^\\s*\\d+[.)、]\\s+");

    public Validation validate(String answer, EvidencePack evidencePack) {
        if (answer == null || answer.isBlank()) {
            return Validation.passed();
        }
        Set<String> availableIds = availableIds(evidencePack);
        Set<String> referencedIds = referencedIds(answer);
        List<String> unknown = referencedIds.stream()
                .filter(id -> !availableIds.contains(id)).sorted().toList();
        if (!unknown.isEmpty()) {
            return Validation.failed(Reason.UNKNOWN_EVIDENCE_ID, unknown);
        }

        LocalDate dataAsOf = evidencePack == null || evidencePack.dataAsOf() == null ? null
                : evidencePack.dataAsOf().atZone(ZoneOffset.UTC).toLocalDate();
        if (dataAsOf != null && containsFutureDate(answer, dataAsOf)) {
            return Validation.failed(Reason.DATE_AFTER_DATA_AS_OF, List.of());
        }

        for (String line : answer.lines().toList()) {
            String claim = URL.matcher(CITATION.matcher(line).replaceAll("")).replaceAll("");
            claim = ORDERED_LIST_PREFIX.matcher(claim).replaceFirst("");
            if (NUMBER.matcher(claim).find() && !hasCurrentEvidenceReference(line, availableIds)) {
                return Validation.failed(Reason.UNSUPPORTED_NUMERIC_CLAIM, List.of());
            }
        }
        return Validation.passed();
    }

    private Set<String> availableIds(EvidencePack pack) {
        Set<String> ids = new LinkedHashSet<>();
        if (pack == null || pack.evidenceByType() == null) {
            return ids;
        }
        pack.evidenceByType().values().forEach(facts -> facts.stream()
                .filter(fact -> fact != null
                        && fact.temporalStatus() != FinancialFact.TemporalStatus.REJECTED)
                .map(FinancialFact::evidenceId)
                .forEach(ids::add));
        return ids;
    }

    private Set<String> referencedIds(String answer) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private boolean hasCurrentEvidenceReference(String line, Set<String> availableIds) {
        Matcher matcher = CITATION.matcher(line);
        while (matcher.find()) {
            if (availableIds.contains(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsFutureDate(String answer, LocalDate dataAsOf) {
        Matcher matcher = DATE.matcher(answer);
        while (matcher.find()) {
            try {
                if (LocalDate.parse(matcher.group(1)).isAfter(dataAsOf)) {
                    return true;
                }
            } catch (DateTimeParseException ignored) {
                // 非法日期由数值引用规则处理，不进行日期猜测。
            }
        }
        return false;
    }

    public enum Reason {
        OK,
        UNKNOWN_EVIDENCE_ID,
        UNSUPPORTED_NUMERIC_CLAIM,
        DATE_AFTER_DATA_AS_OF
    }

    public record Validation(boolean valid, Reason reason, List<String> missingEvidenceIds) {
        public Validation {
            missingEvidenceIds = missingEvidenceIds == null ? List.of() : List.copyOf(missingEvidenceIds);
        }

        static Validation passed() {
            return new Validation(true, Reason.OK, List.of());
        }

        static Validation failed(Reason reason, List<String> missingEvidenceIds) {
            return new Validation(false, reason, missingEvidenceIds);
        }
    }
}
