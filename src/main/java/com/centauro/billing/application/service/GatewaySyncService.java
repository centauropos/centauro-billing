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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Aplica el estado real de la pasarela (ya reconsultado, nunca el payload del webhook) sobre checkout,
 * suscripción y pagos. Lo usan el webhook y los jobs. Cada método es una transacción propia
 * ({@code REQUIRES_NEW}) que bloquea la suscripción, y es idempotente: reprocesar el mismo estado no cambia nada.
 *
 * <p>Las cancelaciones en la pasarela que surgen acá (preapproval reemplazado o descartado) se ejecutan después
 * del commit y son best effort; si fallan, las retoma la conciliación.
 */
@Component
public class GatewaySyncService {

    static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");
    /** Checkouts recientes que se revisan al vincular un débito nuevo para cancelar los otros preapprovals. */
    static final int KNOWN_CHECKOUTS_LIMIT = 20;

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

    public enum DriftAction { NONE, CANCEL, UPDATE_AMOUNT, PAUSE, RESUME }

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
     * se registra el cobro. Su importe es una de las referencias para validar el cobro.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome applyCharge(GatewayChargeSnapshot charge, GatewaySubscriptionSnapshot preapproval, Actor actor,
                               OffsetDateTime now) {
        if (preapproval != null) {
            preapproval(preapproval, actor, now);
        }
        return charge(charge, preapproval, actor, now);
    }

    /**
     * Deriva entre la suscripción local y su preapproval vigente en la pasarela, en este orden:
     * <ol>
     *   <li>baja local posterior al vínculo del débito, con el débito vivo: cancelar (falló la cancelación
     *       después del commit). Una baja anterior al vínculo no cuenta: el tenant volvió a autorizar;</li>
     *   <li>suspensión administrativa con el débito autorizado: pausar;</li>
     *   <li>débito pausado antes de una reactivación administrativa: reanudar. Si la pausa es posterior a la
     *       reactivación, la hizo el pagador y no se toca;</li>
     *   <li>importe distinto del precio: actualizar.</li>
     * </ol>
     * Registra el evento y devuelve qué corregir; la llamada a la pasarela la hace el job, fuera de la transacción.
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
        String preapprovalId = snapshot.gatewaySubscriptionId();
        boolean authorized = snapshot.status() == GatewaySubscriptionStatus.AUTHORIZED;
        boolean paused = snapshot.status() == GatewaySubscriptionStatus.PAUSED;
        if (!authorized && !paused) {
            return Drift.NONE;
        }
        if (canceledAfterLink(subscription, preapprovalId)) {
            reconciled(subscription, snapshot, "cancel", "Baja local con débito vigente en la pasarela", actor, now);
            return new Drift(DriftAction.CANCEL, null);
        }
        if (subscription.getStatus() == SubscriptionStatus.CANCELED) {
            return Drift.NONE;
        }
        if (authorized && subscription.isAdminSuspended()) {
            reconciled(subscription, snapshot, "pause", "Suspensión administrativa con débito autorizado", actor,
                    now);
            return new Drift(DriftAction.PAUSE, null);
        }
        if (paused && !subscription.isAdminSuspended() && pausedBeforeReactivation(subscription, snapshot)) {
            reconciled(subscription, snapshot, "resume", "Reactivación administrativa con débito pausado", actor,
                    now);
            return new Drift(DriftAction.RESUME, null);
        }
        BigDecimal price = subscription.getPriceAmount();
        if (authorized && snapshot.amount() != null && price != null && !sameAmount(snapshot.amount(), price)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("action", "updateAmount");
            data.put("gatewaySubscriptionId", preapprovalId);
            data.put("gatewayAmount", Amounts.format(snapshot.amount()));
            data.put("priceAmount", Amounts.format(price));
            events.append(subscription.recordGatewayNotice(SubscriptionEventType.GATEWAY_AMOUNT_MISMATCH, data,
                    "GATEWAY_AMOUNT_MISMATCH:" + preapprovalId + ":" + Amounts.format(price),
                    actor, null, now));
            return new Drift(DriftAction.UPDATE_AMOUNT, price);
        }
        return Drift.NONE;
    }

    private void reconciled(Subscription subscription, GatewaySubscriptionSnapshot snapshot, String action,
                            String reason, Actor actor, OffsetDateTime now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", action);
        data.put("gatewaySubscriptionId", snapshot.gatewaySubscriptionId());
        data.put("gatewayStatus", snapshot.rawStatus());
        String dedupe = "GATEWAY_RECONCILED:" + action + ":" + snapshot.gatewaySubscriptionId();
        if (!"cancel".equals(action)) {
            // Pausa y reanudación pueden repetirse en ciclos sucesivos de suspensión.
            dedupe += ":" + (subscription.getSuspendedAt() == null ? "-" : subscription.getSuspendedAt().toInstant());
        }
        events.append(subscription.recordGatewayNotice(SubscriptionEventType.GATEWAY_RECONCILED, data, dedupe, actor,
                reason, now));
    }

    /**
     * La baja (inmediata o agendada) es posterior al vínculo del débito, derivado del evento
     * {@code PAYMENT_METHOD_AUTHORIZED}. Sin ese evento (débito vinculado antes de registrarlo) cuenta como posterior.
     */
    private boolean canceledAfterLink(Subscription subscription, String preapprovalId) {
        Optional<OffsetDateTime> canceledAt = cancellationTime(subscription);
        if (canceledAt.isEmpty()) {
            return false;
        }
        return events.findByDedupeKey(subscription.getId(), "PAYMENT_METHOD_AUTHORIZED:" + preapprovalId)
                .map(link -> canceledAt.get().isAfter(link.occurredAt()))
                .orElse(true);
    }

    /**
     * Momento de la baja: {@code canceled_at} si está cancelada; si la baja está agendada, el último
     * {@code CANCELLATION_SCHEDULED} ({@link OffsetDateTime#MAX} si no hay evento: se asume posterior a todo).
     */
    private Optional<OffsetDateTime> cancellationTime(Subscription subscription) {
        if (subscription.getStatus() == SubscriptionStatus.CANCELED) {
            return Optional.of(subscription.getCanceledAt() == null ? OffsetDateTime.MAX
                    : subscription.getCanceledAt());
        }
        if (subscription.isCancelAtPeriodEnd()) {
            return Optional.of(events.findLatestByType(subscription.getId(),
                            SubscriptionEventType.CANCELLATION_SCHEDULED)
                    .map(SubscriptionEvent::occurredAt)
                    .orElse(OffsetDateTime.MAX));
        }
        return Optional.empty();
    }

    private boolean pausedBeforeReactivation(Subscription subscription, GatewaySubscriptionSnapshot snapshot) {
        if (snapshot.lastModified() == null) {
            return false;
        }
        return events.findLatestByType(subscription.getId(), SubscriptionEventType.REACTIVATED)
                .filter(reactivated -> events.findLatestByType(subscription.getId(),
                                SubscriptionEventType.SUSPENDED_BY_ADMIN)
                        .map(suspended -> suspended.occurredAt().isBefore(reactivated.occurredAt()))
                        .orElse(false))
                .map(reactivated -> !snapshot.lastModified().isAfter(reactivated.occurredAt()))
                .orElse(false);
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
        if (status == CheckoutStatus.EXPIRED || status == CheckoutStatus.FAILED) {
            String blocker = lateAuthorizationBlocker(subscription, checkout);
            if (blocker != null) {
                return discardLateAuthorization(subscription, checkout, preapprovalId, blocker, actor, now);
            }
        }
        String previous = subscription.hasGatewaySubscription() && !GatewaySubscriptionStatus.CANCELLED
                .storedValue().equals(subscription.getGatewayStatus()) ? subscription.getGatewaySubscriptionId() : null;
        if (checkout.getGatewayPreapprovalId() == null) {
            checkout.attachGateway(preapprovalId, null, now);
        }
        checkout.markAuthorized(now);
        checkouts.save(checkout);
        List<String> toCancel = supersedeOtherCheckouts(subscription, checkout, preapprovalId, now);
        SubscriptionEvent event = subscription.linkGatewaySubscription(GatewayName.MERCADOPAGO, preapprovalId,
                snapshot.lastModified(), snapshot.nextChargeAt(), checkout.getPlanId(), checkout.getPriceAmount(),
                checkout.getId(), actor, now);
        subscriptions.save(subscription);
        events.append(event);
        if (previous != null && !previous.equals(preapprovalId)) {
            gatewayOperations.cancelQuietlyAfterCommit(previous, "reemplazado por " + preapprovalId);
        }
        for (String other : toCancel) {
            gatewayOperations.cancelQuietlyAfterCommit(other, "otro preapproval de la suscripción, reemplazado por "
                    + preapprovalId);
        }
        return Outcome.processed("débito " + preapprovalId + " autorizado y vinculado");
    }

    /**
     * Un checkout vencido o fallido que se autoriza tarde solo se vincula si no pisa una decisión posterior:
     * suspensión administrativa, débito vigente de un checkout más nuevo o baja posterior al checkout.
     * Devuelve el motivo para descartarlo, o {@code null}.
     */
    private String lateAuthorizationBlocker(Subscription subscription, GatewayCheckout checkout) {
        if (subscription.isAdminSuspended()) {
            return "suspensión administrativa";
        }
        if (subscription.hasGatewaySubscription()
                && !GatewaySubscriptionStatus.CANCELLED.storedValue().equals(subscription.getGatewayStatus())) {
            // Sin checkout conocido o creado en el mismo instante, el débito vigente se conserva.
            boolean newer = checkouts.findByGatewayPreapprovalId(subscription.getGatewaySubscriptionId())
                    .map(c -> !c.getCreatedAt().isBefore(checkout.getCreatedAt()))
                    .orElse(true);
            if (newer) {
                return "hay un débito vigente de un checkout posterior";
            }
        }
        Optional<OffsetDateTime> canceledAt = cancellationTime(subscription);
        if (canceledAt.isPresent() && canceledAt.get().isAfter(checkout.getCreatedAt())) {
            return "baja posterior al checkout";
        }
        return null;
    }

    private Outcome discardLateAuthorization(Subscription subscription, GatewayCheckout checkout,
                                             String preapprovalId, String blocker, Actor actor, OffsetDateTime now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checkoutId", checkout.getId().toString());
        data.put("checkoutStatus", checkout.getStatus().name());
        data.put("gatewaySubscriptionId", preapprovalId);
        events.append(subscription.recordGatewayNotice(SubscriptionEventType.LATE_AUTHORIZATION_DISCARDED, data,
                "LATE_AUTHORIZATION_DISCARDED:" + preapprovalId, actor, blocker, now));
        gatewayOperations.cancelQuietlyAfterCommit(preapprovalId, "autorización tardía descartada del checkout "
                + checkout.getId());
        return Outcome.processed("checkout " + checkout.getId() + " autorizado tarde y descartado (" + blocker
                + "): se cancela el preapproval " + preapprovalId);
    }

    /**
     * Al vincular un débito nuevo: el PENDING que quede pasa a SUPERSEDED y se devuelven los otros preapprovals
     * conocidos que pueden seguir vivos (PENDING, EXPIRED o FAILED con preapproval) para cancelarlos.
     * Los SUPERSEDED, CANCELLED y AUTHORIZED anteriores ya se cancelaron en su transición.
     */
    private List<String> supersedeOtherCheckouts(Subscription subscription, GatewayCheckout linked,
                                                 String preapprovalId, OffsetDateTime now) {
        List<String> toCancel = new ArrayList<>();
        for (GatewayCheckout other : checkouts.findRecentBySubscriptionId(subscription.getId(),
                KNOWN_CHECKOUTS_LIMIT)) {
            if (other.getId().equals(linked.getId())) {
                continue;
            }
            CheckoutStatus status = other.getStatus();
            if (status == CheckoutStatus.PENDING) {
                other.markSuperseded(now);
                checkouts.save(other);
            } else if (status != CheckoutStatus.EXPIRED && status != CheckoutStatus.FAILED) {
                continue;
            }
            String otherPreapproval = other.getGatewayPreapprovalId();
            if (otherPreapproval != null && !otherPreapproval.equals(preapprovalId)
                    && !otherPreapproval.equals(subscription.getGatewaySubscriptionId())) {
                toCancel.add(otherPreapproval);
            }
        }
        return toCancel;
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

    private Outcome charge(GatewayChargeSnapshot charge, GatewaySubscriptionSnapshot preapproval, Actor actor,
                           OffsetDateTime now) {
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
        if (charge.currency() != null && !Amounts.CURRENCY_ARS.equalsIgnoreCase(charge.currency())) {
            return foreignCurrency(subscription, charge, target, actor, now);
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
            case APPROVED -> charge.gatewaySubscriptionId().equals(subscription.getGatewaySubscriptionId())
                    ? approved(subscription, payment, charge, preapproval, actor, now)
                    : fromSupersededDebit(subscription, payment, charge, actor, now);
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
     * Moneda distinta de ARS: no se guarda el pago ({@code ck_payments_currency} solo admite ARS y guardarlo
     * como ARS lo sumaría a los reportes con un importe falso). Importe y moneda reales quedan solo en el evento.
     */
    private Outcome foreignCurrency(Subscription subscription, GatewayChargeSnapshot charge, PaymentStatus target,
                                    Actor actor, OffsetDateTime now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("gatewayPaymentId", charge.chargeId());
        data.put("gatewaySubscriptionId", charge.gatewaySubscriptionId());
        data.put("gatewayStatus", charge.rawStatus());
        data.put("amount", charge.amount() == null ? null : Amounts.format(charge.amount()));
        data.put("currency", charge.currency());
        boolean appended = events.append(subscription.recordGatewayNotice(
                SubscriptionEventType.PAYMENT_CURRENCY_MISMATCH, data,
                "PAYMENT_CURRENCY_MISMATCH:" + charge.chargeId() + ":" + target, actor, null, now));
        return appended
                ? Outcome.processed("cobro " + charge.chargeId() + " en " + charge.currency() + ": no se registra")
                : Outcome.ignored("cobro " + charge.chargeId() + " en " + charge.currency() + " ya informado");
    }

    /**
     * El importe se compara contra el del débito que generó el cobro (el del preapproval reconsultado o, si no
     * vino, el del checkout) y contra el precio vigente: coincidir con cualquiera (tolerancia 0,01) alcanza; así
     * un cobro con el importe viejo después de un cambio de plan se aplica. Menor que todas las referencias: se
     * registra sin aplicar ({@code PAYMENT_AMOUNT_MISMATCH}). Mayor que alguna sin coincidir: se aplica y se
     * informa ({@code PAYMENT_OVERPAID}).
     */
    private Outcome approved(Subscription subscription, Payment payment, GatewayChargeSnapshot charge,
                             GatewaySubscriptionSnapshot preapproval, Actor actor, OffsetDateTime now) {
        BigDecimal amount = charge.amount();
        BigDecimal debitAmount = debitAmount(charge, preapproval);
        BigDecimal price = subscription.getPriceAmount();
        List<BigDecimal> references = new ArrayList<>();
        if (debitAmount != null) {
            references.add(debitAmount);
        }
        if (price != null) {
            references.add(price);
        }
        boolean matches = references.isEmpty()
                || (amount != null && references.stream().anyMatch(r -> sameAmount(amount, r)));
        boolean shortAmount = !matches && (amount == null
                || references.stream().allMatch(r -> amount.compareTo(r) < 0));
        if (shortAmount) {
            Payment saved = payments.save(payment);
            Map<String, Object> data = amountData(saved, charge, debitAmount, price);
            events.append(subscription.recordGatewayNotice(SubscriptionEventType.PAYMENT_AMOUNT_MISMATCH, data,
                    "PAYMENT_AMOUNT_MISMATCH:" + charge.chargeId(), actor, null, now));
            return Outcome.processed("cobro " + charge.chargeId() + " aprobado con importe menor: "
                    + "registrado sin aplicar");
        }
        PeriodCalculator.Period period = periodCalculator.nextPeriod(subscription, now, null);
        Payment saved = payments.save(payment.withPeriod(period.start(), period.end()));
        if (!matches) {
            Map<String, Object> data = amountData(saved, charge, debitAmount, price);
            events.append(subscription.recordGatewayNotice(SubscriptionEventType.PAYMENT_OVERPAID, data,
                    "PAYMENT_OVERPAID:" + charge.chargeId(), actor, null, now));
        }
        PaymentApplier.Result result = paymentApplier.apply(subscription, saved, period.anchorDay(),
                GatewayName.MERCADOPAGO, actor, "Cobro de Mercado Pago", now);
        return Outcome.processed("cobro " + charge.chargeId() + (result.applied() ? " aplicado" : " registrado sin aplicar"));
    }

    private BigDecimal debitAmount(GatewayChargeSnapshot charge, GatewaySubscriptionSnapshot preapproval) {
        if (preapproval != null && preapproval.amount() != null
                && Objects.equals(preapproval.gatewaySubscriptionId(), charge.gatewaySubscriptionId())) {
            return preapproval.amount();
        }
        return checkouts.findByGatewayPreapprovalId(charge.gatewaySubscriptionId())
                .map(GatewayCheckout::getPriceAmount)
                .orElse(null);
    }

    private static Map<String, Object> amountData(Payment saved, GatewayChargeSnapshot charge, BigDecimal debitAmount,
                                                  BigDecimal price) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", saved.id().toString());
        data.put("gatewayPaymentId", charge.chargeId());
        data.put("amount", charge.amount() == null ? null : Amounts.format(charge.amount()));
        data.put("debitAmount", debitAmount == null ? null : Amounts.format(debitAmount));
        data.put("priceAmount", price == null ? null : Amounts.format(price));
        return data;
    }

    /**
     * Cobro aprobado de un preapproval que ya no es el vigente (reemplazado o descartado): se guarda sin aplicar
     * y visible en la plataforma, para que el admin decida (devolución o pago manual). Se vuelve a pedir la
     * cancelación del preapproval.
     */
    private Outcome fromSupersededDebit(Subscription subscription, Payment payment, GatewayChargeSnapshot charge,
                                        Actor actor, OffsetDateTime now) {
        String preapprovalId = charge.gatewaySubscriptionId();
        Payment saved = payments.save(payment.withNotes("Cobro de un débito reemplazado (preapproval "
                + preapprovalId + "): requiere decisión manual"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", saved.id().toString());
        data.put("gatewayPaymentId", charge.chargeId());
        data.put("gatewaySubscriptionId", preapprovalId);
        data.put("currentGatewaySubscriptionId", subscription.getGatewaySubscriptionId());
        data.put("amount", charge.amount() == null ? null : Amounts.format(charge.amount()));
        events.append(subscription.recordGatewayNotice(SubscriptionEventType.PAYMENT_FROM_SUPERSEDED_DEBIT, data,
                "PAYMENT_FROM_SUPERSEDED_DEBIT:" + charge.chargeId(), actor,
                "Cobro de un débito que ya no es el vigente", now));
        gatewayOperations.cancelQuietlyAfterCommit(preapprovalId, "cobro de un débito reemplazado");
        return Outcome.processed("cobro " + charge.chargeId() + " de un débito reemplazado (" + preapprovalId
                + "): registrado sin aplicar");
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

    static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(AMOUNT_TOLERANCE) <= 0;
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
