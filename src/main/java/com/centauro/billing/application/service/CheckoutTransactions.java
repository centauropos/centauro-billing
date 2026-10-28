package com.centauro.billing.application.service;

import com.centauro.billing.domain.enums.CheckoutStatus;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.exception.CheckoutInProgressException;
import com.centauro.billing.domain.exception.CheckoutNotAllowedException;
import com.centauro.billing.domain.exception.CheckoutNotFoundException;
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

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Transacciones cortas del checkout. {@link #prepare} solo persiste el PENDING (sin pasarela).
 * {@link #claimForAuthorize} valida y marca el intento; la llamada a MP queda entre claim y
 * {@link #attach}, fuera de transacción.
 */
@Component
public class CheckoutTransactions {

    private final SubscriptionRepositoryPort subscriptions;
    private final PlanRepositoryPort plans;
    private final CheckoutRepositoryPort checkouts;
    private final AccessPolicy accessPolicy;
    private final AllowedActionsPolicy allowedActionsPolicy;

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

    /**
     * Claim del authorize: {@code alreadyDone} si el checkout ya tiene preapproval o está AUTHORIZED
     * (idempotencia → 200).
     */
    public record AuthorizeClaim(GatewayCheckout checkout, Plan plan, boolean alreadyDone) {
    }

    @Transactional
    public Prepared prepare(String tenantId, String planCode, Actor actor, OffsetDateTime now) {
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
        String email = subscription.getBillingEmail();
        if (email == null || email.isBlank()) {
            throw new InvalidBillingRequestException(
                    "La suscripción no tiene email de facturación");
        }

        String superseded = null;
        Optional<GatewayCheckout> pending = checkouts.findPendingBySubscriptionId(subscription.getId());
        if (pending.isPresent()) {
            GatewayCheckout current = pending.get();
            // Authorize en vuelo (mismo plan u otro): no reutilizar ni reemplazar.
            if (isAuthorizeInProgress(current)) {
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
     * Valida tenant + PENDING + no expirado + {@code canCheckout} (o idempotencia). Marca el claim firme
     * hasta attach / FAILED / {@link #clearAuthorizeClaim}.
     */
    @Transactional
    public AuthorizeClaim claimForAuthorize(String tenantId, UUID checkoutId, OffsetDateTime now) {
        GatewayCheckout checkout = checkouts.findByIdForUpdate(checkoutId)
                .filter(c -> tenantId.equals(c.getTenantId()))
                .orElseThrow(() -> new CheckoutNotFoundException(checkoutId));
        Plan plan = plans.findById(checkout.getPlanId())
                .orElseThrow(() -> new PlanNotFoundException(checkout.getPlanId().toString()));
        if (checkout.getStatus() == CheckoutStatus.AUTHORIZED
                || (checkout.isPending() && checkout.getGatewayPreapprovalId() != null)) {
            return new AuthorizeClaim(checkout, plan, true);
        }
        Subscription subscription = subscriptions.findByTenantIdForUpdate(tenantId)
                .filter(s -> s.getId().equals(checkout.getSubscriptionId()))
                .orElseThrow(() -> new SubscriptionNotFoundException(tenantId));
        AccessDecision decision = accessPolicy.evaluate(subscription, now);
        if (!allowedActionsPolicy.canCheckout(subscription, decision)) {
            throw new CheckoutNotAllowedException(subscription.isAdminSuspended()
                    ? "La suscripción está suspendida por la plataforma"
                    : "La suscripción ya tiene un débito automático autorizado");
        }
        if (!checkout.isPending()) {
            throw new CheckoutNotAllowedException("El checkout no está pendiente de autorización");
        }
        if (checkout.isExpired(now)) {
            throw new CheckoutNotAllowedException("El checkout expiró");
        }
        if (isAuthorizeInProgress(checkout)) {
            throw new CheckoutInProgressException();
        }
        checkout.markAuthorizeAttempted(now);
        return new AuthorizeClaim(checkouts.save(checkout), plan, false);
    }

    /**
     * Authorize en vuelo: PENDING sin preapproval y {@code updatedAt} posterior a la creación.
     * Claim firme (sin ventana temporal) hasta attach, FAILED o clear tras timeout/5xx.
     */
    public static boolean isAuthorizeInProgress(GatewayCheckout checkout) {
        return checkout.isPending() && checkout.getGatewayPreapprovalId() == null
                && checkout.getUpdatedAt() != null && checkout.getCreatedAt() != null
                && checkout.getUpdatedAt().isAfter(checkout.getCreatedAt());
    }

    /**
     * Adjunta el preapproval solo si el checkout sigue PENDING. Si ya no lo está, no muta (el caller
     * cancela el preapproval nuevo fuera de la transacción).
     */
    @Transactional
    public GatewayCheckout attach(UUID checkoutId, RecurringCheckoutResult result, OffsetDateTime now) {
        GatewayCheckout checkout = checkouts.findByIdForUpdate(checkoutId)
                .orElseThrow(() -> new IllegalStateException("Checkout inexistente: " + checkoutId));
        if (!checkout.isPending()) {
            return checkout;
        }
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
     * Tras timeout o 5xx: deja PENDING y libera el claim para reintentar con la misma idempotency key.
     */
    @Transactional
    public void clearAuthorizeClaim(UUID checkoutId) {
        checkouts.findByIdForUpdate(checkoutId)
                .filter(GatewayCheckout::isPending)
                .filter(c -> c.getGatewayPreapprovalId() == null)
                .ifPresent(checkout -> {
                    checkout.clearAuthorizeClaim();
                    checkouts.save(checkout);
                });
    }

    /**
     * Primer cobro: al fin del trial vigente o del período pago en curso, así no se cobra dos veces el mismo
     * tiempo. En cualquier otro caso se omite y MP cobra según su calendario (~1 h tras autorizar).
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
