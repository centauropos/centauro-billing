package com.centauro.billing.infrastructure.mercadopago;

import com.centauro.billing.domain.enums.GatewayChargeStatus;
import com.centauro.billing.domain.enums.GatewaySubscriptionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MercadoPagoStatusMapperTest {

    @ParameterizedTest
    @CsvSource({
            "pending, PENDING",
            "authorized, AUTHORIZED",
            "AUTHORIZED, AUTHORIZED",
            "paused, PAUSED",
            "cancelled, CANCELLED",
            "canceled, CANCELLED",
            "finished, UNKNOWN"
    })
    void preapprovalStatuses(String raw, GatewaySubscriptionStatus expected) {
        assertThat(MercadoPagoStatusMapper.preapprovalStatus(raw)).isEqualTo(expected);
    }

    @Test
    void missingPreapprovalStatusIsUnknown() {
        assertThat(MercadoPagoStatusMapper.preapprovalStatus(null)).isEqualTo(GatewaySubscriptionStatus.UNKNOWN);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "processed, approved, APPROVED",
            "processed, rejected, REJECTED",
            "processed, cancelled, REJECTED",
            "processed, in_process, PENDING",
            "processed, refunded, UNKNOWN",
            "processed, NULL, UNKNOWN",
            "recycling, NULL, REJECTED",
            "scheduled, NULL, PENDING",
            "waiting for gateway, NULL, PENDING",
            "cancelled, NULL, CANCELLED",
            "algo-nuevo, approved, UNKNOWN"
    }, nullValues = "NULL")
    void chargeStatuses(String authorizedStatus, String paymentStatus, GatewayChargeStatus expected) {
        assertThat(MercadoPagoStatusMapper.chargeStatus(authorizedStatus, paymentStatus)).isEqualTo(expected);
    }

    @Test
    void rawChargeStatusFitsTheColumn() {
        assertThat(MercadoPagoStatusMapper.rawChargeStatus("processed", "approved")).isEqualTo("processed/approved");
        assertThat(MercadoPagoStatusMapper.rawChargeStatus("scheduled", null)).isEqualTo("scheduled");
        assertThat(MercadoPagoStatusMapper.rawChargeStatus("x".repeat(30), "y".repeat(30))).hasSize(40);
    }

    @Test
    void parsesIsoAndCompactOffsets() {
        OffsetDateTime expected = OffsetDateTime.of(2026, 10, 11, 10, 0, 0, 0, ZoneOffset.ofHours(-4));

        assertThat(MercadoPagoStatusMapper.parseDate("2026-10-11T10:00:00.000-04:00")).isEqualTo(expected);
        assertThat(MercadoPagoStatusMapper.parseDate("2026-10-11T10:00:00.000-0400")).isEqualTo(expected);
        assertThat(MercadoPagoStatusMapper.parseDate("no-es-fecha")).isNull();
        assertThat(MercadoPagoStatusMapper.parseDate(null)).isNull();
    }
}
