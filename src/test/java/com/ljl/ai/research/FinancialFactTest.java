package com.ljl.ai.research;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FinancialFactTest {

    @Test
    void sameBusinessFactHasStableEvidenceIdAcrossRetrievals() {
        FinancialFact first = fact(Instant.parse("2026-09-05T01:00:00Z"));
        FinancialFact laterRetrieval = fact(Instant.parse("2026-09-05T02:00:00Z"));

        assertEquals(first.evidenceId(), laterRetrieval.evidenceId());
        assertEquals(FinancialFact.TemporalStatus.VERIFIED, first.temporalStatus());
    }

    @Test
    void evidencePackDefensivelyCopiesNestedCollections() {
        List<FinancialFact> marketFacts = new ArrayList<>(List.of(fact(Instant.parse("2026-09-05T01:00:00Z"))));
        Map<FinancialFact.EvidenceType, List<FinancialFact>> facts =
                new EnumMap<>(FinancialFact.EvidenceType.class);
        facts.put(FinancialFact.EvidenceType.MARKET, marketFacts);
        List<String> missing = new ArrayList<>(List.of("北向资金"));

        EvidencePack pack = new EvidencePack(
                new AnalysisContext("600519", LocalDate.of(2026, 9, 5),
                        AnalysisContext.ResearchMode.STANDARD, "e-1", "t-1", "u-1", "s-1"),
                facts,
                missing,
                List.of(),
                Instant.parse("2026-09-05T01:00:00Z"),
                "hash-1",
                "[E1] 收盘价 1488.00 CNY"
        );

        marketFacts.clear();
        missing.clear();

        assertEquals(1, pack.evidenceByType().get(FinancialFact.EvidenceType.MARKET).size());
        assertEquals(List.of("北向资金"), pack.missingItems());
        assertThrows(UnsupportedOperationException.class,
                () -> pack.evidenceByType().get(FinancialFact.EvidenceType.MARKET).clear());
        assertThrows(UnsupportedOperationException.class,
                () -> pack.evidenceByType().put(FinancialFact.EvidenceType.NEWS, List.of()));
    }

    private FinancialFact fact(Instant retrievedAt) {
        return new FinancialFact(
                FinancialFact.EvidenceType.MARKET,
                "close",
                "1488.00",
                "元/股",
                "CNY",
                "2026-09-04",
                LocalDate.of(2026, 9, 4),
                Instant.parse("2026-09-04T07:00:00Z"),
                "market-provider",
                "https://example.test/600519",
                retrievedAt,
                null,
                "snapshot-600519-20260904",
                FinancialFact.TemporalStatus.VERIFIED
        );
    }
}
