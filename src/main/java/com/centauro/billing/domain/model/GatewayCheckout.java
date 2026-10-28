package com.centauro.billing.domain.model;

import com.centauro.billing.domain.enums.CheckoutStatus;
import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.vo.Amounts;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Intento de adhesión al débito recurrente. Su id viaja como {@code external_reference} del preapproval y
 * como clave de idempotencia de la creación. {@code priceAmount} es el precio vigente al crearlo: es lo que
 * queda en la suscripción si el tenant autoriza.
 */
public class GatewayCheckout {

    private final UUID id;
    private final UUID subscriptionId;
    private final String tenantId;
    private final UUID planId;
    private final BigDecimal priceAmount;
    private final String currency;
    private final GatewayName gateway;
    private String gatewayPreapprovalId;
    private String initPoint;
    private final String payerEmail;
    private CheckoutStatus status;
    private final OffsetDateTime startDate;
    private final OffsetDateTime expiresAt;
    private final String requestedBy;
    private final OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public GatewayCheckout(UUID id, UUID subscriptionId, String tenantId, UUID planId, BigDecimal priceAmount,
                           String currency, GatewayName gateway, String gatewayPreapprovalId, String initPoint,
                           String payerEmail, CheckoutStatus status, OffsetDateTime startDate,
                           OffsetDateTime expiresAt, String requestedBy, OffsetDateTime createdAt,
                           OffsetDateTime updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.planId = Objects.requireNonNull(planId, "planId");
        this.priceAmount = Amounts.scale(Objects.requireNonNull(priceAmount, "priceAmount"));
        this.currency = currency == null ? Amounts.CURRENCY_ARS : currency;
        this.gateway = gateway == null ? GatewayName.MERCADOPAGO : gateway;
        this.gatewayPreapprovalId = gatewayPreapprovalId;
        this.initPoint = initPoint;
        this.payerEmail = Objects.requireNonNull(payerEmail, "payerEmail");
        this.status = Objects.requireNonNull(status, "status");
        this.startDate = startDate;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.requestedBy = requestedBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static GatewayCheckout create(Subscription subscription, UUID planId, BigDecimal priceAmount,
                                         String payerEmail, OffsetDateTime startDate, OffsetDateTime expiresAt,
                                         String requestedBy, OffsetDateTime now) {
        return new GatewayCheckout(UUID.randomUUID(), subscription.getId(), subscription.getTenantId(), planId,
                priceAmount, Amounts.CURRENCY_ARS, GatewayName.MERCADOPAGO, null, null, payerEmail,
                CheckoutStatus.PENDING, startDate, expiresAt, requestedBy, now, now);
    }

    public boolean isPending() {
        return status == CheckoutStatus.PENDING;
    }

    public boolean isExpired(OffsetDateTime now) {
        return !expiresAt.isAfter(now);
    }

    /** PENDING y vigente: el tenant puede seguir con el Brick o ver un {@code initPoint} legacy. */
    public boolean isUsable(OffsetDateTime now) {
        return isPending() && !isExpired(now);
    }

    /** Mismo plan, mismo importe y mismo pagador: el checkout pendiente se reutiliza en vez de crear otro. */
    public boolean matches(UUID otherPlanId, BigDecimal amount, String email) {
        return planId.equals(otherPlanId) && priceAmount.compareTo(Amounts.scale(amount)) == 0
                && payerEmail.equalsIgnoreCase(email);
    }

    /**
     * Marca el intento de authorize (bump de {@code updatedAt}): claim firme hasta attach, FAILED o
     * {@link #clearAuthorizeClaim}; otro authorize concurrente ve PENDING sin preapproval y
     * {@code updatedAt > createdAt}.
     */
    public void markAuthorizeAttempted(OffsetDateTime now) {
        this.updatedAt = now;
    }

    /**
     * Libera el claim tras timeout/5xx para poder reintentar con la misma idempotency key, sin pasar a FAILED.
     */
    public void clearAuthorizeClaim() {
        if (createdAt != null) {
            this.updatedAt = createdAt;
        }
    }

    public void attachGateway(String preapprovalId, String link, OffsetDateTime now) {
        this.gatewayPreapprovalId = preapprovalId;
        if (link != null) {
            this.initPoint = link;
        }
        this.updatedAt = now;
    }

    public void markAuthorized(OffsetDateTime now) {
        transition(CheckoutStatus.AUTHORIZED, now);
    }

    public void markExpired(OffsetDateTime now) {
        transition(CheckoutStatus.EXPIRED, now);
    }

    public void markCancelled(OffsetDateTime now) {
        transition(CheckoutStatus.CANCELLED, now);
    }

    public void markSuperseded(OffsetDateTime now) {
        transition(CheckoutStatus.SUPERSEDED, now);
    }

    public void markFailed(OffsetDateTime now) {
        transition(CheckoutStatus.FAILED, now);
    }

    private void transition(CheckoutStatus target, OffsetDateTime now) {
        this.status = target;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public UUID getPlanId() {
        return planId;
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

    public String getGatewayPreapprovalId() {
        return gatewayPreapprovalId;
    }

    public String getInitPoint() {
        return initPoint;
    }

    public String getPayerEmail() {
        return payerEmail;
    }

    public CheckoutStatus getStatus() {
        return status;
    }

    public OffsetDateTime getStartDate() {
        return startDate;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
