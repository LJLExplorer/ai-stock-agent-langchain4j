package com.ljl.ai.research;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimEvidenceGuardTest {
    private final ClaimEvidenceGuard guard = new ClaimEvidenceGuard();

    @Test
    void shouldAcceptNumericAndDateClaimsWithEvidenceFromCurrentPack() {
        ClaimEvidenceGuard.Validation validation = guard.validate(
                "- 收盘价为 1500 元，数据日期 2025-12-31。[evidence:ev-price]", pack("ev-price"));

        assertThat(validation.valid()).isTrue();
        assertThat(validation.reason()).isEqualTo(ClaimEvidenceGuard.Reason.OK);
    }

    @Test
    void shouldRejectUnknownOrCrossPackEvidenceId() {
        ClaimEvidenceGuard.Validation validation = guard.validate(
                "- 收盘价为 1500 元。[evidence:ev-from-another-pack]", pack("ev-price"));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.reason()).isEqualTo(ClaimEvidenceGuard.Reason.UNKNOWN_EVIDENCE_ID);
        assertThat(validation.missingEvidenceIds()).containsExactly("ev-from-another-pack");
    }

    @Test
    void shouldRejectNumericClaimWithoutEvidenceReference() {
        ClaimEvidenceGuard.Validation validation = guard.validate(
                "- 收盘价为 1500 元。", pack("ev-price"));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.reason()).isEqualTo(ClaimEvidenceGuard.Reason.UNSUPPORTED_NUMERIC_CLAIM);
    }

    @Test
    void shouldRejectClaimDateLaterThanEvidencePackDataAsOf() {
        ClaimEvidenceGuard.Validation validation = guard.validate(
                "- 数据日期为 2026-01-01。[evidence:ev-price]", pack("ev-price"));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.reason()).isEqualTo(ClaimEvidenceGuard.Reason.DATE_AFTER_DATA_AS_OF);
    }

    private EvidencePack pack(String evidenceId) {
        LocalDate asOf = LocalDate.of(2025, 12, 31);
        FinancialFact fact = new FinancialFact(evidenceId, FinancialFact.EvidenceType.MARKET,
                "close", "1500", "CNY/share", "CNY", asOf.toString(), asOf,
                asOf.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), "provider", null,
                Instant.parse("2025-12-31T16:00:00Z"), null, "snapshot-1",
                FinancialFact.TemporalStatus.VERIFIED);
        return new EvidencePack(null, Map.of(FinancialFact.EvidenceType.MARKET, List.of(fact)),
                List.of(), List.of(), Instant.parse("2025-12-31T16:00:00Z"), "hash", "ev-price close=1500");
    }
}
