package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.ExtendTrialRequest;
import com.centauro.billing.application.dto.PlatformCancelRequest;
import com.centauro.billing.application.dto.PlatformChangePlanRequest;
import com.centauro.billing.application.dto.ReactivateRequest;
import com.centauro.billing.application.dto.SubscriptionDetailView;
import com.centauro.billing.application.dto.SubscriptionListItem;
import com.centauro.billing.application.dto.SuspendRequest;
import com.centauro.billing.application.dto.TenantSubscriptionView;
import com.centauro.billing.application.service.GatewayOperations;
import com.centauro.billing.application.service.PaymentApplier;
import com.centauro.billing.application.service.SubscriptionViewAssembler;
import com.centauro.billing.domain.enums.PaymentStatus;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.enums.SuspendMode;
import com.centauro.billing.domain.enums.SuspensionReason;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.exception.PlanNotFoundException;
import com.centauro.billing.domain.exception.PlanWithoutPriceException;
import com.centauro.billing.domain.exception.SubscriptionNotFoundException;
import com.centauro.billing.domain.model.Payment;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.PlanPrice;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.model.SubscriptionEvent;
import com.centauro.billing.domain.policy.PeriodCalculator;
import com.centauro.billing.domain.port.CheckoutRepositoryPort;
import com.centauro.billing.domain.port.PaymentRepositoryPort;
import com.centauro.billing.domain.port.PlanRepositoryPort;
import com.centauro.billing.domain.port.SubscriptionEventRepositoryPort;
import com.centauro.billing.domain.port.SubscriptionRepositoryPort;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.domain.vo.PageQuery;
import com.centauro.billing.domain.vo.PageResult;
import com.centauro.billing.domain.vo.SubscriptionSearchCriteria;
import com.centauro.billing.domain.vo.TenantIds;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Acciones del panel de plataforma (PLATFORM_ADMIN). El tenantId viaja en el path. */
@Service
public class PlatformSubscriptionUseCase {

    static final int DETAIL_EVENTS_LIMIT = 100;
    static final int DETAIL_CHECKOUTS_LIMIT = 20;

    private final SubscriptionRepositoryPort subscriptions;
    private final PlanRepositoryPort plans;
    private final PaymentRepositoryPort payments;
    private final SubscriptionEventRepositoryPort events;
    private final CheckoutRepositoryPort checkouts;
    private final GatewayOperations gatewayOperations;
    private final PaymentApplier paymentApplier;
    private final PeriodCalculator periodCalculator;
    private final SubscriptionViewAssembler assembler;
    private final Clock clock;

    public PlatformSubscriptionUseCase(SubscriptionRepositoryPort subscriptions, PlanRepositoryPort plans,
                                       PaymentRepositoryPort payments, SubscriptionEventRepositoryPort events,
                                       CheckoutRepositoryPort checkouts, GatewayOperations gatewayOperations,
                                       PaymentApplier paymentApplier, PeriodCalculator periodCalculator, SubscriptionViewAssembler assembler,
                                       Clock clock) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.payments = payments;
        this.events = events;
        this.checkouts = checkouts;
        this.gatewayOperations = gatewayOperations;
        this.paymentApplier = paymentApplier;
        this.periodCalculator = periodCalculator;
        this.assembler = assembler;
        this.clock = clock;
    }

    public record SearchFilters(SubscriptionStatus status, String planCode, String tenantId, String query,
                                OffsetDateTime trialEndsBefore) {
    }

    @Transactional(readOnly = true)
    public PageResult<SubscriptionListItem> list(SearchFilters filters, PageQuery pageQuery) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Map<UUID, Plan> planById = plans.findAll().stream()
                .collect(Collectors.toMap(Plan::getId, Function.identity()));
        UUID planId = null;
        if (filters.planCode() != null && !filters.planCode().isBlank()) {
            Optional<Plan> plan = planById.values().stream()
                    .filter(p -> p.getCode().equals(filters.planCode())).findFirst();
            if (plan.isEmpty()) {
                return new PageResult<>(List.of(), pageQuery.page(), pageQuery.size(), 0, 0);
            }
            planId = plan.get().getId();
        }
        SubscriptionSearchCriteria criteria = new SubscriptionSearchCriteria(filters.status(), planId,
                blankToNull(filters.tenantId()), blankToNull(filters.query()), filters.trialEndsBefore());
        return subscriptions.search(criteria, pageQuery)
                .map(s -> assembler.listItem(s, planById.get(s.getPlanId()), now));
    }

    @Transactional(readOnly = true)
    public SubscriptionDetailView detail(String tenantId) {
        TenantIds.requireValid(tenantId);
        Subscription subscription = subscriptions.findByTenantId(tenantId)
                .orElseThrow(() -> new SubscriptionNotFoundException(tenantId));
        OffsetDateTime now = OffsetDateTime.now(clock);
        TenantSubscriptionView view = tenantView(subscription, now);
        return new SubscriptionDetailView(view, subscription.getCompanyName(), subscription.getBillingEmail(),
                subscription.getGateway() == null ? null : subscription.getGateway().name(),
                subscription.getGatewaySubscriptionId(), subscription.getGatewayStatus(),
                subscription.getVersion(),
                events.findRecentBySubscriptionId(subscription.getId(), DETAIL_EVENTS_LIMIT).stream()
                        .map(assembler::eventView).toList(),
                payments.findBySubscriptionId(subscription.getId()).stream()
                        .map(assembler::paymentView).toList(),
                checkouts.findRecentBySubscriptionId(subscription.getId(), DETAIL_CHECKOUTS_LIMIT).stream()
                        .map(assembler::checkoutSummary).toList());
    }

    @Transactional
    public TenantSubscriptionView extendTrial(String tenantId, ExtendTrialRequest request, Actor actor) {
        boolean hasDays = request.days() != null;
        boolean hasDate = request.trialEndsAt() != null;
        if (hasDays == hasDate) {
            throw new InvalidBillingRequestException("Indicar exactamente uno de days o trialEndsAt");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        Subscription subscription = lock(tenantId);
        OffsetDateTime newEnd = request.trialEndsAt();
        if (hasDays) {
            OffsetDateTime current = subscription.getTrialEndsAt();
            OffsetDateTime base = current != null && current.isAfter(now) ? current : now;
            newEnd = periodCalculator.startOfDayAfter(base, request.days());
        }
        SubscriptionEvent event = subscription.extendTrial(newEnd, actor, request.reason(), now);
        return persist(subscription, event, now);
    }

    @Transactional
    public TenantSubscriptionView changePlan(String tenantId, PlatformChangePlanRequest request, Actor actor) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Subscription subscription = lock(tenantId);
        Plan plan = plans.findByCode(request.planCode())
                .filter(Plan::isActive)
                .orElseThrow(() -> new PlanNotFoundException(request.planCode()));
        BigDecimal amount = plan.resolveCustomAmount(request.customAmount())
                .orElseGet(() -> plan.currentPrice(now).map(PlanPrice::amount)
                        .orElseThrow(() -> new PlanWithoutPriceException(request.planCode())));
        boolean syncGateway = !Boolean.FALSE.equals(request.syncGateway());
        if (syncGateway) {
            gatewayOperations.requireAvailable(subscription);
        }
        SubscriptionEvent event = subscription.changePlan(plan.getId(), plan.getCode(), amount, actor,
                request.reason(), now);
        TenantSubscriptionView view = persist(subscription, event, now);
        if (syncGateway) {
            gatewayOperations.updateAmountAfterCommit(subscription, amount);
        }
        return view;
    }

    @Transactional
    public TenantSubscriptionView suspend(String tenantId, SuspendRequest request, Actor actor) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Subscription subscription = lock(tenantId);
        SuspensionReason reason = request.mode() == SuspendMode.BLOCKED
                ? SuspensionReason.ADMIN_BLOCK : SuspensionReason.ADMIN_READ_ONLY;
        SubscriptionEvent event = subscription.suspendByAdmin(reason, actor, request.reason(), now);
        TenantSubscriptionView view = persist(subscription, event, now);
        gatewayOperations.pauseQuietlyAfterCommit(subscription);
        return view;
    }

    /**
     * Levanta la suspensión o la baja y aplica, en orden de cobro, los pagos aprobados que quedaron sin aplicar
     * (los cobros recibidos durante la suspensión administrativa). Se excluyen los de un débito reemplazado y los
     * de importe menor: esos los decide el admin. Cada pago extiende el período desde el fin anterior.
     *
     * <p>{@code currentPeriodEnd} es opcional si hay pagos para aplicar: el primero abre el período desde hoy.
     * Si viene, fija el fin y los pagos se suman a continuación. Si la suspensión era administrativa, el débito
     * de la pasarela se reanuda después del commit.
     */
    @Transactional
    public TenantSubscriptionView reactivate(String tenantId, ReactivateRequest request, Actor actor) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Subscription subscription = lock(tenantId);
        boolean adminSuspended = subscription.isAdminSuspended();
        List<Payment> pending = pendingPayments(subscription);
        OffsetDateTime newEnd = request.currentPeriodEnd();
        PeriodCalculator.Period firstPeriod = null;
        if (newEnd == null) {
            if (pending.isEmpty()) {
                throw new InvalidBillingRequestException(
                        "Indicar currentPeriodEnd: no hay pagos sin aplicar que cubran el período");
            }
            firstPeriod = periodCalculator.nextPeriod(subscription, now, null);
            newEnd = firstPeriod.end();
        }
        int anchorDay = firstPeriod != null ? firstPeriod.anchorDay() : periodCalculator.dayOf(newEnd);
        SubscriptionEvent event = subscription.reactivate(newEnd, anchorDay, actor, request.reason(), now);
        subscription = subscriptions.save(subscription);
        events.append(event);
        for (Payment payment : pending) {
            PeriodCalculator.Period period = firstPeriod != null ? firstPeriod
                    : periodCalculator.nextPeriod(subscription, now, null);
            firstPeriod = null;
            Payment saved = payments.save(payment.withPeriod(period.start(), period.end()));
            subscription = paymentApplier.apply(subscription, saved, period.anchorDay(), subscription.getGateway(),
                    actor, "Pago sin aplicar, aplicado al reactivar", now).subscription();
        }
        if (adminSuspended) {
            gatewayOperations.resumeQuietlyAfterCommit(subscription);
        }
        return tenantView(subscription, now);
    }

    private List<Payment> pendingPayments(Subscription subscription) {
        return payments.findBySubscriptionId(subscription.getId()).stream()
                .filter(p -> p.status() == PaymentStatus.APPROVED && !p.applied())
                .filter(p -> p.gatewayPaymentId() == null || (
                        events.findByDedupeKey(subscription.getId(),
                                "PAYMENT_FROM_SUPERSEDED_DEBIT:" + p.gatewayPaymentId()).isEmpty()
                        && events.findByDedupeKey(subscription.getId(),
                                "PAYMENT_AMOUNT_MISMATCH:" + p.gatewayPaymentId()).isEmpty()))
                .sorted(Comparator.comparing((Payment p) -> p.paidAt() == null ? p.createdAt() : p.paidAt())
                        .thenComparing(Payment::createdAt))
                .toList();
    }

    @Transactional
    public TenantSubscriptionView cancel(String tenantId, PlatformCancelRequest request, Actor actor) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Subscription subscription = lock(tenantId);
        gatewayOperations.requireAvailable(subscription);
        SubscriptionEvent event = Boolean.TRUE.equals(request.immediate())
                ? subscription.cancelImmediately(actor, request.reason(), now)
                : subscription.cancel(actor, request.reason(), now);
        TenantSubscriptionView view = persist(subscription, event, now);
        gatewayOperations.cancelAfterCommit(subscription);
        return view;
    }

    private TenantSubscriptionView persist(Subscription subscription, SubscriptionEvent event, OffsetDateTime now) {
        Subscription saved = subscriptions.save(subscription);
        events.append(event);
        return tenantView(saved, now);
    }

    private Subscription lock(String tenantId) {
        TenantIds.requireValid(tenantId);
        return subscriptions.findByTenantIdForUpdate(tenantId)
                .orElseThrow(() -> new SubscriptionNotFoundException(tenantId));
    }

    private TenantSubscriptionView tenantView(Subscription subscription, OffsetDateTime now) {
        return assembler.tenantView(subscription, plans.findById(subscription.getPlanId()).orElse(null), now);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
