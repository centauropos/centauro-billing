package com.centauro.billing.application.service;

import com.centauro.billing.domain.enums.CheckoutStatus;
import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.enums.GatewaySubscriptionStatus;
import com.centauro.billing.domain.enums.PaymentSource;
import com.centauro.billing.domain.enums.PaymentStatus;
import com.centauro.billing.domain.enums.SubscriptionEventType;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.model.GatewayCheckout;
import com.centauro.billing.domain.model.Payment;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.model.SubscriptionEvent;
import com.centauro.billing.domain.policy.PeriodCalculator;
import com.centauro.billing.domain.port.CheckoutRepositoryPort;
import com.centauro.billing.domain.port.PaymentRepositoryPort;
import com.centauro.billing.domain.port.SubscriptionEventRepositoryPort;
import com.centauro.billing.domain.port.SubscriptionRepositoryPort;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.domain.vo.Amounts;
import com.centauro.billing.domain.vo.GatewayChargeSnapshot;
import com.centauro.billing.domain.vo.GatewaySubscriptionSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Aplica el estado real de la pasarela (ya reconsultado, nunca el payload del webhook) sobre checkout,
 * suscripción y pagos. Lo usan el webhook y la conciliación. Cada método es una transacción propia
 * ({@code REQUIRES_NEW}: los jobs corren dentro de la transacción del advisory lock) que bloquea la suscripción,
 * y es idempotente: reprocesar el mismo estado no cambia nada.
 *
 * <p>Las cancelaciones en la pasarela que surgen acá (preapproval reemplazado) se ejecutan después del commit
 * y son best effort; si fallan, las retoma la conciliación.
 */
@Component
public class GatewaySyncService {

    static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");

    private final SubscriptionRepositoryPort subscriptions;
    private final CheckoutRepositoryPort checkouts;
    private final PaymentRepositoryPort payments;
    private final SubscriptionEventRepositoryPort events;
    private final PaymentApplier paymentApplier;
    private final PeriodCalculator periodCalculator;
    private final GatewayOperations gatewayOperations;

    public GatewaySyncService(SubscriptionRepositoryPort subscriptions, CheckoutRepositoryPort checkouts,
                              PaymentRepositoryPort payments, SubscriptionEventRepositoryPort events,
                              PaymentApplier paymentApplier, PeriodCalculator periodCalculator,
                              GatewayOperations gatewayOperations) {
        this.subscriptions = subscriptions;
        this.checkouts = checkouts;
        this.payments = payments;
        this.events = events;
        this.paymentApplier = paymentApplier;
        this.periodCalculator = periodCalculator;
        this.gatewayOperations = gatewayOperations;
    }

    /** {@code processed = false} es IGNORED: no había nada que hacer y no se reintenta. */
    public record Outcome(boolean processed, String detail) {

        static Outcome processed(String detail) {
            return new Outcome(true, detail);
        }

        static Outcome ignored(String detail) {
            return new Outcome(false, detail);
        }
    }

    public enum DriftAction { NONE, CANCEL, UPDATE_AMOUNT }

    public record Drift(DriftAction action, BigDecimal amount) {

        static final Drift NONE = new Drift(DriftAction.NONE, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isLinked(String gatewaySubscriptionId) {
        return subscriptions.findByGatewaySubscriptionId(gatewaySubscriptionId).isPresent();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome applyPreapproval(GatewaySubscriptionSnapshot snapshot, Actor actor, OffsetDateTime now) {
        return preapproval(snapshot, actor, now);
    }

    /**
     * {@code preapproval} viene cuando el cobro llegó antes que la autorización: se vincula primero y después
     * se registra el cobro.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome applyCharge(GatewayChargeSnapshot charge, GatewaySubscriptionSnapshot preapproval, Actor actor,
                               OffsetDateTime now) {
        if (preapproval != null) {
            preapproval(preapproval, actor, now);
        }
        return charge(charge, actor, now);
    }

    /**
     * Deriva entre la suscripción local y un preapproval vigente en la pasarela: baja local con débito vivo
     * (falló la cancelación después del commit) o importe distinto (falló el cambio de importe). Registra el
     * evento y devuelve qué corregir; la llamada a la pasarela la hace el job, fuera de la transacción.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Drift detectDrift(UUID subscriptionId, GatewaySubscriptionSnapshot snapshot, Actor actor,
                             OffsetDateTime now) {
        Optional<Subscription> locked = subscriptions.findById(subscriptionId)
                .flatMap(s -> subscriptions.findByTenantIdForUpdate(s.getTenantId()));
        if (locked.isEmpty() || !snapshot.gatewaySubscriptionId().equals(locked.get().getGatewaySubscriptionId())) {
            return Drift.NONE;
        }
        Subscription subscription = locked.get();
        boolean live = snapshot.status() == GatewaySubscriptionStatus.AUTHORIZED
                || snapshot.status() == GatewaySubscriptionStatus.PAUSED;
        if (!live) {
            return Drift.NONE;
        }
        if (subscription.getStatus() == SubscriptionStatus.CANCELED
                || subscription.isCancelAtPeriodEnd()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("action", "cancel");
            data.put("gatewaySubscriptionId", snapshot.gatewaySubscriptionId());
            data.put("gatewayStatus", snapshot.rawStatus());
            events.append(subscription.recordGatewayNotice(SubscriptionEventType.GATEWAY_RECONCILED, data,
                    "GATEWAY_RECONCILED:cancel:" + snapshot.gatewaySubscriptionId(), actor,
                    "Baja local con débito vigente en la pasarela", now));
            return new Drift(DriftAction.CANCEL, null);
        }
        BigDecimal price = subscription.getPriceAmount();
        if (snapshot.status() == GatewaySubscriptionStatus.AUTHORIZED && snapshot.amount() != null && price != null
                && snapshot.amount().subtract(price).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("action", "updateAmount");
            data.put("gatewaySubscriptionId", snapshot.gatewaySubscriptionId());
            data.put("gatewayAmount", Amounts.format(snapshot.amount()));
            data.put("priceAmount", Amounts.format(price));
            events.append(subscription.recordGatewayNotice(SubscriptionEventType.GATEWAY_AMOUNT_MISMATCH, data,
                    "GATEWAY_AMOUNT_MISMATCH:" + snapshot.gatewaySubscriptionId() + ":" + Amounts.format(price),
                    actor, null, now));
            return new Drift(DriftAction.UPDATE_AMOUNT, price);
        }
        return Drift.NONE;
    }

    // ---------------------------------------------------------------- preapproval

    private Outcome preapproval(GatewaySubscriptionSnapshot snapshot, Actor actor, OffsetDateTime now) {
        String preapprovalId = snapshot.gatewaySubscriptionId();
        if (preapprovalId == null || preapprovalId.isBlank()) {
            return Outcome.ignored("preapproval sin id");
        }
        Optional<GatewayCheckout> found = resolveCheckout(snapshot);
        Subscription subscription;
        GatewayCheckout checkout = null;
        if (found.isPresent()) {
            subscription = subscriptions.findByTenantIdForUpdate(found.get().getTenantId()).orElse(null);
            checkout = checkouts.findByIdForUpdate(found.get().getId()).orElse(null);
        } else {
            subscription = subscriptions.findByGatewaySubscriptionId(preapprovalId)
                    .flatMap(s -> subscriptions.findByTenantIdForUpdate(s.getTenantId()))
                    .orElse(null);
        }
        if (subscription == null) {
            return Outcome.ignored("preapproval " + preapprovalId + " sin checkout ni suscripción vinculada");
        }
        boolean current = preapprovalId.equals(subscription.getGatewaySubscriptionId());
        if (current && isStale(subscription, snapshot)) {
            return Outcome.ignored("preapproval " + preapprovalId + ": notificación desactualizada (last_modified)");
        }
        return switch (snapshot.status()) {
            case AUTHORIZED -> authorized(subscription, checkout, snapshot, current, actor, now);
            case PAUSED, CANCELLED -> stopped(subscription, checkout, snapshot, current, actor, now);
            case PENDING -> pending(checkout, snapshot, now);
            case UNKNOWN -> Outcome.ignored("preapproval " + preapprovalId + " con estado desconocido: "
                    + snapshot.rawStatus());
        };
    }

    /** Por {@code external_reference} (= id del checkout); si no coincide, por id de preapproval. */
    private Optional<GatewayCheckout> resolveCheckout(GatewaySubscriptionSnapshot snapshot) {
        UUID reference = parseUuid(snapshot.externalReference());
        if (reference != null) {
            Optional<GatewayCheckout> byReference = checkouts.findById(reference);
            if (byReference.isPresent() && (byReference.get().getGatewayPreapprovalId() == null
                    || byReference.get().getGatewayPreapprovalId().equals(snapshot.gatewaySubscriptionId()))) {
                return byReference;
            }
        }
        return checkouts.findByGatewayPreapprovalId(snapshot.gatewaySubscriptionId());
    }

    private static boolean isStale(Subscription subscription, GatewaySubscriptionSnapshot snapshot) {
        return subscription.getGatewayLastModified() != null && snapshot.lastModified() != null
                && !snapshot.lastModified().isAfter(subscription.getGatewayLastModified());
    }

    private Outcome authorized(Subscription subscription, GatewayCheckout checkout,
                               GatewaySubscriptionSnapshot snapshot, boolean current, Actor actor,
                               OffsetDateTime now) {
        String preapprovalId = snapshot.gatewaySubscriptionId();
        if (current) {
            SubscriptionEvent event = subscription.syncGatewayState(GatewaySubscriptionStatus.AUTHORIZED,
                    snapshot.lastModified(), snapshot.nextChargeAt(), actor, now);
            subscriptions.save(subscription);
            if (event != null) {
                events.append(event);
            }
            if (checkout != null && checkout.isPending()) {
                checkout.markAuthorized(now);
                checkouts.save(checkout);
            }
            return Outcome.processed("preapproval vigente " + preapprovalId + " autorizado");
        }
        if (checkout == null) {
            return Outcome.ignored("preapproval " + preapprovalId + " autorizado sin checkout");
        }
        CheckoutStatus status = checkout.getStatus();
        if (status == CheckoutStatus.SUPERSEDED || status == CheckoutStatus.CANCELLED
                || status == CheckoutStatus.AUTHORIZED) {
            // AUTHORIZED acá es un checkout que ya fue reemplazado por un débito posterior.
            gatewayOperations.cancelQuietlyAfterCommit(preapprovalId, "checkout " + checkout.getId() + " " + status);
            return Outcome.ignored("checkout " + checkout.getId() + " " + status
                    + ": se cancela el preapproval " + preapprovalId);
        }
        String previous = subscription.hasGatewaySubscription() && !GatewaySubscriptionStatus.CANCELLED
                .storedValue().equals(subscription.getGatewayStatus()) ? subscription.getGatewaySubscriptionId() : null;
        if (checkout.getGatewayPreapprovalId() == null) {
            checkout.attachGateway(preapprovalId, null, now);
        }
        checkout.markAuthorized(now);
        checkouts.save(checkout);
        GatewayCheckout authorizedCheckout = checkout;
        checkouts.findPendingBySubscriptionId(subscription.getId())
                .filter(other -> !other.getId().equals(authorizedCheckout.getId()))
                .ifPresent(other -> {
                    other.markSuperseded(now);
                    checkouts.save(other);
                    gatewayOperations.cancelQuietlyAfterCommit(other.getGatewayPreapprovalId(),
                            "checkout " + other.getId() + " reemplazado");
                });
        SubscriptionEvent event = subscription.linkGatewaySubscription(GatewayName.MERCADOPAGO, preapprovalId,
                snapshot.lastModified(), snapshot.nextChargeAt(), checkout.getPlanId(), checkout.getPriceAmount(),
                checkout.getId(), actor, now);
        subscriptions.save(subscription);
        events.append(event);
        if (previous != null && !previous.equals(preapprovalId)) {
            gatewayOperations.cancelQuietlyAfterCommit(previous, "reemplazado por " + preapprovalId);
        }
        return Outcome.processed("débito " + preapprovalId + " autorizado y vinculado");
    }

    private Outcome stopped(Subscription subscription, GatewayCheckout checkout, GatewaySubscriptionSnapshot snapshot,
                            boolean current, Actor actor, OffsetDateTime now) {
        String preapprovalId = snapshot.gatewaySubscriptionId();
        if (current) {
            SubscriptionEvent event = subscription.syncGatewayState(snapshot.status(), snapshot.lastModified(),
                    snapshot.nextChargeAt(), actor, now);
            subscriptions.save(subscription);
            if (event != null) {
                events.append(event);
            }
            return Outcome.processed("preapproval vigente " + preapprovalId + " " + snapshot.rawStatus());
        }
        if (checkout != null && checkout.isPending() && snapshot.status() == GatewaySubscriptionStatus.CANCELLED) {
            checkout.markCancelled(now);
            checkouts.save(checkout);
            return Outcome.processed("checkout " + checkout.getId() + " cancelado en la pasarela");
        }
        return Outcome.ignored("preapproval " + preapprovalId + " no vigente (" + snapshot.rawStatus() + ")");
    }

    private Outcome pending(GatewayCheckout checkout, GatewaySubscriptionSnapshot snapshot, OffsetDateTime now) {
        if (checkout != null && checkout.isPending() && checkout.getGatewayPreapprovalId() == null) {
            checkout.attachGateway(snapshot.gatewaySubscriptionId(), null, now);
            checkouts.save(checkout);
        }
        return Outcome.ignored("preapproval " + snapshot.gatewaySubscriptionId() + " pendiente de autorización");
    }

    // ---------------------------------------------------------------- cobros

    private Outcome charge(GatewayChargeSnapshot charge, Actor actor, OffsetDateTime now) {
        String chargeId = charge.chargeId();
        if (chargeId == null || chargeId.isBlank()) {
            return Outcome.ignored("cobro sin id");
        }
        Optional<Subscription> locked = resolveChargeSubscription(charge.gatewaySubscriptionId());
        if (locked.isEmpty()) {
            return Outcome.ignored("cobro " + chargeId + " de un preapproval no vinculado: "
                    + charge.gatewaySubscriptionId());
        }
        Subscription subscription = locked.get();
        PaymentStatus target = switch (charge.status()) {
            case APPROVED -> PaymentStatus.APPROVED;
            case REJECTED -> PaymentStatus.REJECTED;
            case PENDING -> PaymentStatus.PENDING;
            case CANCELLED -> PaymentStatus.CANCELLED;
            case UNKNOWN -> null;
        };
        if (target == null) {
            return Outcome.ignored("cobro " + chargeId + " con estado desconocido: " + charge.rawStatus());
        }
        Optional<Payment> existing = payments.findByGatewayPaymentId(PaymentSource.MERCADOPAGO, chargeId);
        Payment payment;
        if (existing.isPresent()) {
            Payment current = existing.get();
            if (current.status() == PaymentStatus.APPROVED) {
                return Outcome.ignored("cobro " + chargeId + " ya registrado como aprobado");
            }
            if (current.status() == target) {
                return Outcome.ignored("cobro " + chargeId + " sin cambios (" + target + ")");
            }
            payment = current.withGatewayState(target, charge.amount(), Amounts.CURRENCY_ARS, charge.transactionId(),
                    charge.rawStatus(), charge.chargedAt());
        } else {
            payment = Payment.fromGateway(subscription, PaymentSource.MERCADOPAGO, target, charge.amount(),
                    Amounts.CURRENCY_ARS, chargeId, charge.transactionId(), charge.rawStatus(), charge.chargedAt(),
                    actor.name(), now);
        }
        return switch (target) {
            case APPROVED -> approved(subscription, payment, charge, actor, now);
            case REJECTED -> rejected(subscription, payment, charge, actor, now);
            default -> {
                payments.save(payment);
                yield Outcome.processed("cobro " + chargeId + " " + target);
            }
        };
    }

    private Optional<Subscription> resolveChargeSubscription(String preapprovalId) {
        if (preapprovalId == null || preapprovalId.isBlank()) {
            return Optional.empty();
        }
        Optional<Subscription> linked = subscriptions.findByGatewaySubscriptionId(preapprovalId);
        if (linked.isEmpty()) {
            linked = checkouts.findByGatewayPreapprovalId(preapprovalId)
                    .flatMap(c -> subscriptions.findById(c.getSubscriptionId()));
        }
        return linked.flatMap(s -> subscriptions.findByTenantIdForUpdate(s.getTenantId()));
    }

    /**
     * Importe menor al precio (tolerancia 0,01) o moneda distinta de ARS: se registra sin aplicar. La moneda
     * se guarda como ARS ({@code ck_payments_currency}); la real queda en el evento.
     */
    private Outcome approved(Subscription subscription, Payment payment, GatewayChargeSnapshot charge, Actor actor,
                             OffsetDateTime now) {
        boolean wrongCurrency = charge.currency() != null && !Amounts.CURRENCY_ARS.equalsIgnoreCase(charge.currency());
        BigDecimal price = subscription.getPriceAmount();
        boolean shortAmount = price != null
                && (charge.amount() == null || charge.amount().add(AMOUNT_TOLERANCE).compareTo(price) < 0);
        if (wrongCurrency || shortAmount) {
            Payment saved = payments.save(payment);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("paymentId", saved.id().toString());
            data.put("gatewayPaymentId", charge.chargeId());
            data.put("amount", charge.amount() == null ? null : Amounts.format(charge.amount()));
            data.put("currency", charge.currency());
            data.put("priceAmount", price == null ? null : Amounts.format(price));
            events.append(subscription.recordGatewayNotice(SubscriptionEventType.PAYMENT_AMOUNT_MISMATCH, data,
                    "PAYMENT_AMOUNT_MISMATCH:" + charge.chargeId(), actor, null, now));
            return Outcome.processed("cobro " + charge.chargeId() + " aprobado con importe o moneda distintos: "
                    + "registrado sin aplicar");
        }
        PeriodCalculator.Period period = periodCalculator.nextPeriod(subscription, now, null);
        Payment saved = payments.save(payment.withPeriod(period.start(), period.end()));
        PaymentApplier.Result result = paymentApplier.apply(subscription, saved, period.anchorDay(),
                GatewayName.MERCADOPAGO, actor, "Cobro de Mercado Pago", now);
        return Outcome.processed("cobro " + charge.chargeId() + (result.applied() ? " aplicado" : " registrado sin aplicar"));
    }

    private Outcome rejected(Subscription subscription, Payment payment, GatewayChargeSnapshot charge, Actor actor,
                             OffsetDateTime now) {
        Payment saved = payments.save(payment);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", saved.id().toString());
        data.put("gatewayPaymentId", charge.chargeId());
        data.put("gatewayStatus", charge.rawStatus());
        data.put("amount", charge.amount() == null ? null : Amounts.format(charge.amount()));
        events.append(subscription.recordGatewayNotice(SubscriptionEventType.PAYMENT_REJECTED, data,
                "PAYMENT_REJECTED:" + charge.chargeId(), actor, null, now));
        return Outcome.processed("cobro " + charge.chargeId() + " rechazado");
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
