package com.centauro.billing.support;

import com.centauro.billing.application.service.CheckoutTransactions;
import com.centauro.billing.application.service.GatewayOperations;
import com.centauro.billing.application.service.GatewaySettings;
import com.centauro.billing.application.service.GatewaySyncService;
import com.centauro.billing.application.service.PaymentApplier;
import com.centauro.billing.application.usecase.CheckoutUseCase;
import com.centauro.billing.application.usecase.WebhookUseCase;
import com.centauro.billing.domain.enums.GatewayChargeStatus;
import com.centauro.billing.domain.enums.GatewaySubscriptionStatus;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.policy.AccessPolicy;
import com.centauro.billing.domain.policy.AllowedActionsPolicy;
import com.centauro.billing.domain.policy.PeriodCalculator;
import com.centauro.billing.domain.port.PaymentGateway;
import com.centauro.billing.domain.vo.GatewayChargeSnapshot;
import com.centauro.billing.domain.vo.GatewaySubscriptionSnapshot;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.centauro.billing.support.TestFixtures.AR;
import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.TERMS;

/** Checkout, webhook y conciliación cableados con repos en memoria y la pasarela falsa. */
public final class GatewayHarness {

    public static final String BACK_URL = "https://app.test/billing";
    public static final int MAX_ATTEMPTS = 3;

    public final InMemorySubscriptionRepository subscriptions = new InMemorySubscriptionRepository();
    public final InMemoryPlanRepository plans = new InMemoryPlanRepository();
    public final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
    public final InMemoryEventRepository events = new InMemoryEventRepository();
    public final InMemoryCheckoutRepository checkouts = new InMemoryCheckoutRepository();
    public final InMemoryWebhookEventRepository webhookEvents = new InMemoryWebhookEventRepository();
    public final FakePaymentGateway gateway = new FakePaymentGateway();
    public final GatewayOperations gatewayOperations;
    public final GatewaySettings settings;
    public final Clock clock;
    public final GatewaySyncService sync;
    public final CheckoutTransactions checkoutTransactions;
    public final CheckoutUseCase checkoutUseCase;
    public final WebhookUseCase webhookUseCase;

    public GatewayHarness() {
        this(true, BACK_URL);
    }

    public GatewayHarness(boolean withGateway, String backUrl) {
        clock = TestFixtures.clockAt(NOW);
        gatewayOperations = new GatewayOperations(withGateway ? Optional.<PaymentGateway>of(gateway) : Optional.empty());
        settings = new GatewaySettings(Duration.ofHours(24), backUrl, MAX_ATTEMPTS);
        PeriodCalculator periodCalculator = new PeriodCalculator(AR);
        sync = new GatewaySyncService(subscriptions, checkouts, payments, events,
                new PaymentApplier(subscriptions, payments, events), periodCalculator, gatewayOperations);
        checkoutTransactions = new CheckoutTransactions(subscriptions, plans, checkouts, new AccessPolicy(TERMS),
                new AllowedActionsPolicy(), settings);
        checkoutUseCase = new CheckoutUseCase(checkoutTransactions, gatewayOperations, settings,
                TestFixtures.assembler(checkouts, payments, gatewayOperations), clock);
        webhookUseCase = new WebhookUseCase(webhookEvents, sync, gatewayOperations, settings, clock);
    }

    public Subscription store(Subscription subscription) {
        subscriptions.put(subscription);
        return subscription;
    }

    public GatewaySubscriptionSnapshot preapproval(String id, GatewaySubscriptionStatus status, UUID checkoutId,
                                                   BigDecimal amount, OffsetDateTime lastModified) {
        GatewaySubscriptionSnapshot snapshot = new GatewaySubscriptionSnapshot(id, status,
                status.name().toLowerCase(), checkoutId == null ? null : checkoutId.toString(), amount, "ARS",
                NOW.plusMonths(1), lastModified);
        gateway.subscriptions.put(id, snapshot);
        return snapshot;
    }

    public GatewayChargeSnapshot charge(String id, String preapprovalId, GatewayChargeStatus status,
                                        BigDecimal amount) {
        return charge(id, preapprovalId, status, amount, "ARS");
    }

    public GatewayChargeSnapshot charge(String id, String preapprovalId, GatewayChargeStatus status,
                                        BigDecimal amount, String currency) {
        GatewayChargeSnapshot snapshot = new GatewayChargeSnapshot(id, preapprovalId, "pay-" + id, status,
                status.name().toLowerCase(), amount, currency, NOW, NOW);
        gateway.charges.put(id, snapshot);
        return snapshot;
    }
}
