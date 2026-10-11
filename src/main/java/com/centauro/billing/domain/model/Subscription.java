package com.centauro.billing.domain.model;

import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.enums.PastDueReason;
import com.centauro.billing.domain.enums.SubscriptionEventType;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.exception.InvalidSubscriptionTransitionException;
import com.centauro.billing.domain.exception.SamePlanException;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.domain.vo.Amounts;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.centauro.billing.domain.enums.SubscriptionStatus.ACTIVE;
import static com.centauro.billing.domain.enums.SubscriptionStatus.CANCELED;
import static com.centauro.billing.domain.enums.SubscriptionStatus.PAST_DUE;
import static com.centauro.billing.domain.enums.SubscriptionStatus.SUSPENDED;
import static com.centauro.billing.domain.enums.SubscriptionStatus.TRIALING;

/**
 * Agregado de suscripción. El estado solo cambia a través de los métodos de transición, que validan
 * el estado de origen y devuelven el {@link SubscriptionEvent} que hay que persistir.
 */
public class Subscription {

    private final UUID id;
    private final String tenantId;
    private UUID planId;
    private SubscriptionStatus status;
    private SuspensionReason suspensionReason;
    private PastDueReason pastDueReason;
    private OffsetDateTime trialEndsAt;
    private OffsetDateTime currentPeriodStart;
    private OffsetDateTime currentPeriodEnd;
    private Integer billingAnchorDay;
    private OffsetDateTime graceEndsAt;
    private boolean cancelAtPeriodEnd;
    private OffsetDateTime canceledAt;
    private OffsetDateTime suspendedAt;
    private BigDecimal priceAmount;
    private final String currency;
    private GatewayName gateway;
    private final String gatewaySubscriptionId;
    private final String gatewayStatus;
    private final OffsetDateTime gatewayLastModified;
    private final OffsetDateTime nextChargeAt;
    private final String billingEmail;
    private final String companyName;
    private final Long version;
    private final OffsetDateTime createdAt;

    public Subscription(UUID id, String tenantId, UUID planId, SubscriptionStatus status,
                        SuspensionReason suspensionReason, PastDueReason pastDueReason,
                        OffsetDateTime trialEndsAt, OffsetDateTime currentPeriodStart,
                        OffsetDateTime currentPeriodEnd, Integer billingAnchorDay, OffsetDateTime graceEndsAt,
                        boolean cancelAtPeriodEnd, OffsetDateTime canceledAt, OffsetDateTime suspendedAt,
                        BigDecimal priceAmount, String currency, GatewayName gateway, String gatewaySubscriptionId,
                        String gatewayStatus, OffsetDateTime gatewayLastModified, OffsetDateTime nextChargeAt,
                        String billingEmail, String companyName, Long version, OffsetDateTime createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.planId = Objects.requireNonNull(planId, "planId");
        this.status = Objects.requireNonNull(status, "status");
        this.suspensionReason = suspensionReason;
        this.pastDueReason = pastDueReason;
        this.trialEndsAt = trialEndsAt;
        this.currentPeriodStart = currentPeriodStart;
        this.currentPeriodEnd = currentPeriodEnd;
        this.billingAnchorDay = billingAnchorDay == null ? null : requireAnchorDay(billingAnchorDay);
        this.graceEndsAt = graceEndsAt;
        this.cancelAtPeriodEnd = cancelAtPeriodEnd;
        this.canceledAt = canceledAt;
        this.suspendedAt = suspendedAt;
        this.priceAmount = Amounts.scale(priceAmount);
        this.currency = currency == null ? Amounts.CURRENCY_ARS : currency;
        this.gateway = gateway;
        this.gatewaySubscriptionId = gatewaySubscriptionId;
        this.gatewayStatus = gatewayStatus;
        this.gatewayLastModified = gatewayLastModified;
        this.nextChargeAt = nextChargeAt;
        this.billingEmail = billingEmail;
        this.companyName = companyName;
        this.version = version;
        this.createdAt = createdAt;
    }

    // ---------------------------------------------------------------- T1

    public static Subscription startTrial(String tenantId, UUID planId, BigDecimal priceAmount,
                                          OffsetDateTime trialEndsAt, String companyName, String billingEmail,
                                          OffsetDateTime now) {
        return new Subscription(UUID.randomUUID(), tenantId, planId, TRIALING, null, null, trialEndsAt,
                null, null, null, null, false, null, null, priceAmount, Amounts.CURRENCY_ARS, null, null, null,
                null, null, billingEmail, companyName, null, now);
    }

    public SubscriptionEvent trialStartedEvent(Actor actor, String reason, String source, OffsetDateTime now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planId", planId.toString());
        data.put("trialEndsAt", trialEndsAt.toString());
        data.put("source", source);
        return SubscriptionEvent.of(this, SubscriptionEventType.TRIAL_STARTED, null, actor, reason, data,
                "TRIAL_STARTED", now);
    }

    // ---------------------------------------------------------------- T2 / T2b

    public SubscriptionEvent extendTrial(OffsetDateTime newTrialEndsAt, Actor actor, String reason,
                                         OffsetDateTime now) {
        Objects.requireNonNull(newTrialEndsAt, "newTrialEndsAt");
        SubscriptionStatus from = status;
        OffsetDateTime previous = trialEndsAt;
        if (status == TRIALING) {
            if (!newTrialEndsAt.isAfter(trialEndsAt) || !newTrialEndsAt.isAfter(now)) {
                throw new InvalidBillingRequestException(
                        "La nueva fecha de fin de trial debe ser posterior a la actual y a hoy");
            }
        } else if (status == PAST_DUE && pastDueReason == PastDueReason.TRIAL_EXPIRED) {
            if (!newTrialEndsAt.isAfter(now)) {
                throw new InvalidBillingRequestException("La nueva fecha de fin de trial debe ser posterior a hoy");
            }
            status = TRIALING;
            pastDueReason = null;
            graceEndsAt = null;
        } else {
            throw invalid("extender el trial de");
        }
        trialEndsAt = newTrialEndsAt;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("previousTrialEndsAt", previous == null ? null : previous.toString());
        data.put("trialEndsAt", newTrialEndsAt.toString());
        return SubscriptionEvent.of(this, SubscriptionEventType.TRIAL_EXTENDED, from, actor, reason, data,
                null, now);
    }

    // ---------------------------------------------------------------- T3 / T4

    public boolean isTrialExpired(OffsetDateTime now) {
        return status == TRIALING && !trialEndsAt.isAfter(now);
    }

    public SubscriptionEvent expireTrial(int trialGraceDays, Actor actor, OffsetDateTime now) {
        if (!isTrialExpired(now)) {
            throw invalid("vencer el trial de");
        }
        SubscriptionStatus from = status;
        String dedupeKey = "TRIAL_EXPIRED:" + trialEndsAt.toInstant();
        if (cancelAtPeriodEnd) {
            status = CANCELED;
            canceledAt = now;
            cancelAtPeriodEnd = false;
            return SubscriptionEvent.of(this, SubscriptionEventType.TRIAL_ENDED_CANCELED, from, actor,
                    null, null, dedupeKey, now);
        }
        status = PAST_DUE;
        pastDueReason = PastDueReason.TRIAL_EXPIRED;
        graceEndsAt = trialEndsAt.plusDays(trialGraceDays);
        return SubscriptionEvent.of(this, SubscriptionEventType.TRIAL_EXPIRED, from, actor, null,
                Map.of("graceEndsAt", graceEndsAt.toString()), dedupeKey, now);
    }

    // ---------------------------------------------------------------- T6 / T7

    public boolean isPeriodExpired(OffsetDateTime now) {
        return status == ACTIVE && !currentPeriodEnd.isAfter(now);
    }

    public SubscriptionEvent endPeriod(int graceDays, Actor actor, OffsetDateTime now) {
        if (!isPeriodExpired(now)) {
            throw invalid("cerrar el período de");
        }
        SubscriptionStatus from = status;
        String dedupeKey = "PERIOD_UNPAID:" + currentPeriodEnd.toInstant();
        if (cancelAtPeriodEnd) {
            status = CANCELED;
            canceledAt = now;
            cancelAtPeriodEnd = false;
            return SubscriptionEvent.of(this, SubscriptionEventType.PERIOD_ENDED_CANCELED, from, actor,
                    null, null, dedupeKey, now);
        }
        status = PAST_DUE;
        pastDueReason = PastDueReason.PERIOD_UNPAID;
        graceEndsAt = currentPeriodEnd.plusDays(graceDays);
        return SubscriptionEvent.of(this, SubscriptionEventType.PERIOD_UNPAID, from, actor, null,
                Map.of("graceEndsAt", graceEndsAt.toString()), dedupeKey, now);
    }

    // ---------------------------------------------------------------- T8

    public boolean isGraceExpired(OffsetDateTime now) {
        return status == PAST_DUE && graceEndsAt != null && !graceEndsAt.isAfter(now);
    }

    public SubscriptionEvent suspendForNonPayment(Actor actor, OffsetDateTime now) {
        if (!isGraceExpired(now)) {
            throw invalid("suspender por falta de pago");
        }
        SubscriptionStatus from = status;
        String dedupeKey = "SUSPENDED_NON_PAYMENT:" + graceEndsAt.toInstant();
        status = SUSPENDED;
        suspensionReason = SuspensionReason.NON_PAYMENT;
        suspendedAt = now;
        return SubscriptionEvent.of(this, SubscriptionEventType.SUSPENDED_NON_PAYMENT, from, actor, null,
                null, dedupeKey, now);
    }

    // ---------------------------------------------------------------- T5

    public boolean isAdminSuspended() {
        return status == SUSPENDED && suspensionReason != SuspensionReason.NON_PAYMENT;
    }

    /** {@code anchorDay} es el día del mes en que vencen los períodos siguientes (ver {@code PeriodCalculator}). */
    public SubscriptionEvent applyPayment(UUID paymentId, OffsetDateTime periodStart, OffsetDateTime periodEnd,
                                          int anchorDay, GatewayName paymentGateway, Actor actor, String reason,
                                          OffsetDateTime now) {
        if (isAdminSuspended()) {
            throw invalid("aplicar un pago con suspensión administrativa a");
        }
        if (periodEnd == null || periodStart == null || !periodEnd.isAfter(periodStart)) {
            throw new InvalidBillingRequestException("El fin del período debe ser posterior al inicio");
        }
        requireAnchorDay(anchorDay);
        SubscriptionStatus from = status;
        status = ACTIVE;
        currentPeriodStart = periodStart;
        currentPeriodEnd = periodEnd;
        billingAnchorDay = anchorDay;
        graceEndsAt = null;
        pastDueReason = null;
        suspensionReason = null;
        suspendedAt = null;
        if (from == CANCELED) {
            canceledAt = null;
            cancelAtPeriodEnd = false;
        }
        if (gateway == null) {
            gateway = paymentGateway;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", paymentId.toString());
        data.put("periodStart", periodStart.toString());
        data.put("periodEnd", periodEnd.toString());
        return SubscriptionEvent.of(this, SubscriptionEventType.PAYMENT_APPLIED, from, actor, reason, data,
                "PAYMENT_APPLIED:" + paymentId, now);
    }

    /** Un pago aprobado con suspensión administrativa se registra pero no reactiva (solo T12 la levanta). */
    public SubscriptionEvent recordPaymentWithoutReactivation(UUID paymentId, Actor actor, String reason,
                                                              OffsetDateTime now) {
        return SubscriptionEvent.of(this, SubscriptionEventType.PAYMENT_RECORDED, status, actor, reason,
                Map.of("paymentId", paymentId.toString(), "applied", false), "PAYMENT_RECORDED:" + paymentId, now);
    }

    // ---------------------------------------------------------------- T9 / T10

    /**
     * Cancelación pedida por el tenant o por la plataforma sin {@code immediate}.
     * TRIALING y ACTIVE agendan la baja a fin de período (T9). PAST_DUE y SUSPENDED por falta de pago
     * cancelan en el acto (T10), porque no queda período pago por consumir.
     */
    public SubscriptionEvent cancel(Actor actor, String reason, OffsetDateTime now) {
        SubscriptionStatus from = status;
        if ((status == TRIALING || status == ACTIVE) && !cancelAtPeriodEnd) {
            cancelAtPeriodEnd = true;
            return SubscriptionEvent.of(this, SubscriptionEventType.CANCELLATION_SCHEDULED, from, actor, reason,
                    null, null, now);
        }
        if (status == PAST_DUE || (status == SUSPENDED && suspensionReason == SuspensionReason.NON_PAYMENT)) {
            return markCanceled(from, actor, reason, now);
        }
        if (cancelAtPeriodEnd) {
            throw new InvalidSubscriptionTransitionException("La cancelación ya está agendada");
        }
        throw invalid("cancelar");
    }

    /** Cancelación inmediata desde la plataforma: cualquier estado salvo CANCELED. */
    public SubscriptionEvent cancelImmediately(Actor actor, String reason, OffsetDateTime now) {
        if (status == CANCELED) {
            throw invalid("cancelar");
        }
        return markCanceled(status, actor, reason, now);
    }

    private SubscriptionEvent markCanceled(SubscriptionStatus from, Actor actor, String reason,
                                           OffsetDateTime now) {
        status = CANCELED;
        canceledAt = now;
        cancelAtPeriodEnd = false;
        suspensionReason = null;
        suspendedAt = null;
        pastDueReason = null;
        graceEndsAt = null;
        return SubscriptionEvent.of(this, SubscriptionEventType.CANCELED, from, actor, reason, null, null, now);
    }

    // ---------------------------------------------------------------- T11

    public SubscriptionEvent suspendByAdmin(SuspensionReason reasonCode, Actor actor, String reason,
                                            OffsetDateTime now) {
        if (reasonCode != SuspensionReason.ADMIN_READ_ONLY && reasonCode != SuspensionReason.ADMIN_BLOCK) {
            throw new InvalidBillingRequestException("Motivo de suspensión administrativa inválido");
        }
        if (status == CANCELED) {
            throw invalid("suspender");
        }
        if (status == SUSPENDED && suspensionReason == reasonCode) {
            throw new InvalidSubscriptionTransitionException("La suscripción ya está suspendida con ese modo");
        }
        SubscriptionStatus from = status;
        status = SUSPENDED;
        suspensionReason = reasonCode;
        suspendedAt = now;
        return SubscriptionEvent.of(this, SubscriptionEventType.SUSPENDED_BY_ADMIN, from, actor, reason,
                Map.of("suspensionReason", reasonCode.name()), null, now);
    }

    // ---------------------------------------------------------------- T12

    /** {@code anchorDay} es el día del mes de {@code newPeriodEnd} en la zona de negocio. */
    public SubscriptionEvent reactivate(OffsetDateTime newPeriodEnd, int anchorDay, Actor actor, String reason,
                                        OffsetDateTime now) {
        if (status != SUSPENDED && status != CANCELED) {
            throw invalid("reactivar");
        }
        if (newPeriodEnd == null || !newPeriodEnd.isAfter(now)) {
            throw new InvalidBillingRequestException("El fin del período debe ser posterior a hoy");
        }
        requireAnchorDay(anchorDay);
        SubscriptionStatus from = status;
        status = ACTIVE;
        if (currentPeriodStart == null || !currentPeriodStart.isBefore(newPeriodEnd)) {
            currentPeriodStart = now;
        }
        currentPeriodEnd = newPeriodEnd;
        billingAnchorDay = anchorDay;
        suspensionReason = null;
        suspendedAt = null;
        pastDueReason = null;
        graceEndsAt = null;
        canceledAt = null;
        cancelAtPeriodEnd = false;
        return SubscriptionEvent.of(this, SubscriptionEventType.REACTIVATED, from, actor, reason,
                Map.of("currentPeriodEnd", newPeriodEnd.toString()), null, now);
    }

    // ---------------------------------------------------------------- T13

    public SubscriptionEvent changePlan(UUID newPlanId, String newPlanCode, BigDecimal newPriceAmount,
                                        Actor actor, String reason, OffsetDateTime now) {
        if (status == CANCELED) {
            throw invalid("cambiar el plan de");
        }
        if (planId.equals(newPlanId)) {
            throw new SamePlanException(newPlanCode);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fromPlanId", planId.toString());
        data.put("toPlanId", newPlanId.toString());
        data.put("toPlanCode", newPlanCode);
        data.put("fromPrice", Amounts.format(priceAmount));
        data.put("toPrice", Amounts.format(newPriceAmount));
        planId = newPlanId;
        priceAmount = Amounts.scale(newPriceAmount);
        return SubscriptionEvent.of(this, SubscriptionEventType.PLAN_CHANGED, status, actor, reason, data,
                null, now);
    }

    // ----------------------------------------------------------------

    public boolean hasGatewaySubscription() {
        return gatewaySubscriptionId != null && !gatewaySubscriptionId.isBlank();
    }

    private static int requireAnchorDay(int anchorDay) {
        if (anchorDay < 1 || anchorDay > 31) {
            throw new InvalidBillingRequestException("El día ancla debe estar entre 1 y 31");
        }
        return anchorDay;
    }

    private InvalidSubscriptionTransitionException invalid(String action) {
        String suffix = status == SUSPENDED ? " (" + suspensionReason + ")" : "";
        return new InvalidSubscriptionTransitionException(
                "No se puede " + action + " una suscripción " + status + suffix);
    }

    public UUID getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public UUID getPlanId() {
        return planId;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public SuspensionReason getSuspensionReason() {
        return suspensionReason;
    }

    public PastDueReason getPastDueReason() {
        return pastDueReason;
    }

    public OffsetDateTime getTrialEndsAt() {
        return trialEndsAt;
    }

    public OffsetDateTime getCurrentPeriodStart() {
        return currentPeriodStart;
    }

    public OffsetDateTime getCurrentPeriodEnd() {
        return currentPeriodEnd;
    }

    public Integer getBillingAnchorDay() {
        return billingAnchorDay;
    }

    public OffsetDateTime getGraceEndsAt() {
        return graceEndsAt;
    }

    public boolean isCancelAtPeriodEnd() {
        return cancelAtPeriodEnd;
    }

    public OffsetDateTime getCanceledAt() {
        return canceledAt;
    }

    public OffsetDateTime getSuspendedAt() {
        return suspendedAt;
    }

    public BigDecimal getPriceAmount() {
        return priceAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public GatewayName getGateway() {
        return gateway;
    }

    public String getGatewaySubscriptionId() {
        return gatewaySubscriptionId;
    }

    public String getGatewayStatus() {
        return gatewayStatus;
    }

    public OffsetDateTime getGatewayLastModified() {
        return gatewayLastModified;
    }

    public OffsetDateTime getNextChargeAt() {
        return nextChargeAt;
    }

    public String getBillingEmail() {
        return billingEmail;
    }

    public String getCompanyName() {
        return companyName;
    }

    public Long getVersion() {
        return version;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
