package com.centauro.billing.infrastructure.mercadopago;

import com.centauro.billing.domain.enums.GatewayChargeStatus;
import com.centauro.billing.domain.enums.GatewaySubscriptionStatus;
import com.centauro.billing.domain.vo.GatewayChargeSnapshot;
import com.centauro.billing.domain.vo.GatewaySubscriptionSnapshot;
import com.centauro.billing.infrastructure.mercadopago.dto.MpAuthorizedPayment;
import com.centauro.billing.infrastructure.mercadopago.dto.MpPreapproval;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Traduce los estados de MP al modelo. Toda suposición sobre nombres de estados vive acá; un estado que no se
 * reconoce cae en {@code UNKNOWN} y el webhook lo marca IGNORED con el valor crudo, sin tocar la suscripción.
 */
final class MercadoPagoStatusMapper {

    /** MP a veces manda el offset sin dos puntos ({@code -0400}). */
    private static final DateTimeFormatter COMPACT_OFFSET = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .appendPattern("XX")
            .toFormatter(Locale.ROOT);

    private MercadoPagoStatusMapper() {
    }

    static GatewaySubscriptionSnapshot preapproval(MpPreapproval p) {
        return new GatewaySubscriptionSnapshot(p.id(), preapprovalStatus(p.status()), p.status(),
                p.externalReference(),
                p.autoRecurring() == null ? null : p.autoRecurring().transactionAmount(),
                p.autoRecurring() == null ? null : p.autoRecurring().currencyId(),
                parseDate(p.nextPaymentDate()), parseDate(p.lastModified()));
    }

    static GatewayChargeSnapshot authorizedPayment(MpAuthorizedPayment a) {
        String paymentStatus = a.payment() == null ? null : a.payment().status();
        String paymentId = a.payment() == null ? null : a.payment().id();
        OffsetDateTime chargedAt = parseDate(a.debitDate());
        return new GatewayChargeSnapshot(a.id(), a.preapprovalId(), paymentId,
                chargeStatus(a.status(), paymentStatus), rawChargeStatus(a.status(), paymentStatus),
                a.transactionAmount(), a.currencyId(), chargedAt != null ? chargedAt : parseDate(a.dateCreated()),
                parseDate(a.lastModified()));
    }

    /** Se aceptan las dos grafías de cancelado: verificar cuál devuelve MP. */
    static GatewaySubscriptionStatus preapprovalStatus(String raw) {
        if (raw == null) {
            return GatewaySubscriptionStatus.UNKNOWN;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "pending" -> GatewaySubscriptionStatus.PENDING;
            case "authorized" -> GatewaySubscriptionStatus.AUTHORIZED;
            case "paused" -> GatewaySubscriptionStatus.PAUSED;
            case "cancelled", "canceled" -> GatewaySubscriptionStatus.CANCELLED;
            default -> GatewaySubscriptionStatus.UNKNOWN;
        };
    }

    /**
     * {@code processed} + pago {@code approved}: cobrado. {@code recycling} (MP reintenta) o {@code processed}
     * + pago rechazado: rechazado. {@code scheduled}, {@code waiting for gateway} o pago en proceso: pendiente.
     * Reintegros y contracargos no se interpretan (UNKNOWN): se resuelven a mano.
     */
    static GatewayChargeStatus chargeStatus(String authorizedStatus, String paymentStatus) {
        if (authorizedStatus == null) {
            return GatewayChargeStatus.UNKNOWN;
        }
        String status = authorizedStatus.trim().toLowerCase(Locale.ROOT);
        return switch (status) {
            case "cancelled", "canceled" -> GatewayChargeStatus.CANCELLED;
            case "recycling" -> GatewayChargeStatus.REJECTED;
            case "scheduled", "waiting for gateway", "waiting_for_gateway", "pending", "in_process" ->
                    GatewayChargeStatus.PENDING;
            case "processed" -> processedPayment(paymentStatus);
            default -> GatewayChargeStatus.UNKNOWN;
        };
    }

    private static GatewayChargeStatus processedPayment(String paymentStatus) {
        if (paymentStatus == null) {
            return GatewayChargeStatus.UNKNOWN;
        }
        return switch (paymentStatus.trim().toLowerCase(Locale.ROOT)) {
            case "approved" -> GatewayChargeStatus.APPROVED;
            case "rejected", "cancelled", "canceled" -> GatewayChargeStatus.REJECTED;
            case "pending", "in_process", "authorized" -> GatewayChargeStatus.PENDING;
            default -> GatewayChargeStatus.UNKNOWN;
        };
    }

    /** Lo que se guarda en {@code payments.gateway_status} (máx. 40). */
    static String rawChargeStatus(String authorizedStatus, String paymentStatus) {
        String raw = paymentStatus == null ? String.valueOf(authorizedStatus) : authorizedStatus + "/" + paymentStatus;
        return raw.length() <= 40 ? raw : raw.substring(0, 40);
    }

    static OffsetDateTime parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(value, COMPACT_OFFSET);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
