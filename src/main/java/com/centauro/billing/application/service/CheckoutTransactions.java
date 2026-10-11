package com.centauro.billing.application.service;

import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.exception.CheckoutInProgressException;
import com.centauro.billing.domain.exception.CheckoutNotAllowedException;
import com.centauro.billing.domain.exception.CheckoutPlanRequiresContactException;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.exception.PlanNotFoundException;
import com.centauro.billing.domain.exception.PlanWithoutPriceException;
import com.centauro.billing.domain.exception.SubscriptionNotFoundException;
import com.centauro.billing.domain.model.AccessDecision;
import com.centauro.billing.domain.model.GatewayCheckout;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.PlanPrice;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.policy.AccessPolicy;
import com.centauro.billing.domain.policy.AllowedActionsPolicy;
import com.centauro.billing.domain.port.CheckoutRepositoryPort;
import com.centauro.billing.domain.port.PlanRepositoryPort;
import com.centauro.billing.domain.port.SubscriptionRepositoryPort;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.domain.vo.RecurringCheckoutResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Las transacciones cortas del checkout. La llamada a la pasarela queda entre {@link #prepare} y
 * {@link #attach}, fuera de toda transacción: el checkout se persiste antes de pedir el preapproval, así un
 * preapproval creado por un POST que dio timeout igual encuentra su checkout por {@code external_reference}.
 */
@Component
public class CheckoutTransactions {

    private final SubscriptionRepositoryPort subscriptions;
    private final PlanRepositoryPort plans;
    private final CheckoutRepositoryPort checkouts;
    private final AccessPolicy accessPolicy;
    private final AllowedActionsPolicy allowedActionsPolicy;
    static final Duration IN_PROGRESS_WINDOW = Duration.ofSeconds(30);

    private final GatewaySettings settings;

    public CheckoutTransactions(SubscriptionRepositoryPort subscriptions, PlanRepositoryPort plans,
                                CheckoutRepositoryPort checkouts, AccessPolicy accessPolicy,
                                AllowedActionsPolicy allowedActionsPolicy, GatewaySettings settings) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.checkouts = checkouts;
        this.accessPolicy = accessPolicy;
        this.allowedActionsPolicy = allowedActionsPolicy;
        this.settings = settings;
    }

    /** {@code supersededPreapprovalId}: preapproval del checkout reemplazado, a cancelar en la pasarela. */
    public record Prepared(GatewayCheckout checkout, Plan plan, boolean reused, String supersededPreapprovalId) {
    }

    @Transactional
    public Prepared prepare(String tenantId, String planCode, String payerEmail, Actor actor, OffsetDateTime now) {
        Subscription subscription = subscriptions.findByTenantIdForUpdate(tenantId)
                .orElseThrow(() -> new SubscriptionNotFoundException(tenantId));
        Plan plan = plans.findByCode(planCode)
                .filter(Plan::isOfferable)
                .orElseThrow(() -> new PlanNotFoundException(planCode));
        if (plan.isCustomPricing()) {
            throw new CheckoutPlanRequiresContactException(planCode);
        }
        PlanPrice price = plan.currentPrice(now).orElseThrow(() -> new PlanWithoutPriceException(planCode));
        AccessDecision decision = accessPolicy.evaluate(subscription, now);
        if (!allowedActionsPolicy.canCheckout(subscription, decision)) {
            throw new CheckoutNotAllowedException(subscription.isAdminSuspended()
                    ? "La suscripción está suspendida por la plataforma"
                    : "La suscripción ya tiene un débito automático autorizado");
        }
        String email = payerEmail != null && !payerEmail.isBlank() ? payerEmail.trim() : subscription.getBillingEmail();
        if (email == null || email.isBlank()) {
            throw new InvalidBillingRequestException(
                    "La suscripción no tiene email de facturación: indicar payerEmail");
        }

        String superseded = null;
        Optional<GatewayCheckout> pending = checkouts.findPendingBySubscriptionId(subscription.getId());
        if (pending.isPresent()) {
            GatewayCheckout current = pending.get();
            if (isInProgress(current, plan, now)) {
                throw new CheckoutInProgressException();
            }
            if (current.isUsable(now) && current.matches(plan.getId(), price.amount(), email)) {
                return new Prepared(current, plan, true, null);
            }
            if (current.isExpired(now)) {
                current.markExpired(now);
            } else {
                current.markSuperseded(now);
            }
            checkouts.save(current);
            superseded = current.getGatewayPreapprovalId();
        }
        GatewayCheckout checkout = GatewayCheckout.create(subscription, plan.getId(), price.amount(), email,
                startDate(subscription, decision, now), now.plus(settings.checkoutExpiration()), actor.name(), now);
        return new Prepared(checkouts.save(checkout), plan, false, superseded);
    }

    /**
     * Doble click: un PENDING del mismo plan, todavía sin {@code init_point} y creado hace menos de
     * {@link #IN_PROGRESS_WINDOW}, es otro pedido que está llamando a MP. No se reemplaza: 409.
     */
    private static boolean isInProgress(GatewayCheckout pending, Plan plan, OffsetDateTime now) {
        return pending.getInitPoint() == null && pending.getPlanId().equals(plan.getId())
                && pending.getCreatedAt() != null && pending.getCreatedAt().isAfter(now.minus(IN_PROGRESS_WINDOW));
    }

    /** Devuelve el checkout tal como quedó: si ya no está PENDING, otro pedido lo reemplazó mientras tanto. */
    @Transactional
    public GatewayCheckout attach(UUID checkoutId, RecurringCheckoutResult result, OffsetDateTime now) {
        GatewayCheckout checkout = checkouts.findByIdForUpdate(checkoutId)
                .orElseThrow(() -> new IllegalStateException("Checkout inexistente: " + checkoutId));
        checkout.attachGateway(result.gatewaySubscriptionId(), result.initPoint(), now);
        return checkouts.save(checkout);
    }

    @Transactional
    public void markFailed(UUID checkoutId, OffsetDateTime now) {
        checkouts.findByIdForUpdate(checkoutId).filter(GatewayCheckout::isPending).ifPresent(checkout -> {
            checkout.markFailed(now);
            checkouts.save(checkout);
        });
    }

    /**
     * Primer cobro: al fin del trial vigente o del período pago en curso, así no se cobra dos veces el mismo
     * tiempo. En cualquier otro caso se omite y MP cobra al autorizar.
     */
    static OffsetDateTime startDate(Subscription subscription, AccessDecision decision, OffsetDateTime now) {
        SubscriptionStatus status = decision.effectiveStatus();
        if (status == SubscriptionStatus.TRIALING && subscription.getTrialEndsAt() != null
                && subscription.getTrialEndsAt().isAfter(now)) {
            return subscription.getTrialEndsAt();
        }
        if (status == SubscriptionStatus.ACTIVE && subscription.getCurrentPeriodEnd() != null
                && subscription.getCurrentPeriodEnd().isAfter(now)) {
            return subscription.getCurrentPeriodEnd();
        }
        return null;
    }
}
