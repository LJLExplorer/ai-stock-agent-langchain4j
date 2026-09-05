package com.ljl.ai.research;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一次分析可共享的不可变证据集合。
 */
public record EvidencePack(
        AnalysisContext context,
        Map<FinancialFact.EvidenceType, List<FinancialFact>> evidenceByType,
        List<String> missingItems,
        List<String> toolFailures,
        Instant dataAsOf,
        String evidenceHash,
        String modelView
) {
    public EvidencePack {
        EnumMap<FinancialFact.EvidenceType, List<FinancialFact>> copied =
                new EnumMap<>(FinancialFact.EvidenceType.class);
        if (evidenceByType != null) {
            evidenceByType.forEach((type, facts) -> copied.put(type,
                    facts == null ? List.of() : List.copyOf(facts)));
        }
        evidenceByType = Collections.unmodifiableMap(copied);
        missingItems = missingItems == null ? List.of() : List.copyOf(missingItems);
        toolFailures = toolFailures == null ? List.of() : List.copyOf(toolFailures);
        modelView = modelView == null ? "" : modelView;
    }
}
