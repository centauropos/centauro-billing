package com.centauro.billing.support;

import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.enums.GatewaySubscriptionStatus;
import com.centauro.billing.domain.port.PaymentGateway;
import com.centauro.billing.domain.vo.GatewayChargeSnapshot;
import com.centauro.billing.domain.vo.GatewaySubscriptionSnapshot;
import com.centauro.billing.domain.vo.RecurringCheckoutRequest;
import com.centauro.billing.domain.vo.RecurringCheckoutResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pasarela falsa para tests: registra las llamadas y nunca sale a la red. */
public class FakePaymentGateway implements PaymentGateway {

    public final List<RecurringCheckoutRequest> created = new ArrayList<>();
    public final List<String> canceled = new ArrayList<>();
    public final List<String> amountUpdates = new ArrayList<>();
    public final List<String> paused = new ArrayList<>();
    public final List<String> resumed = new ArrayList<>();
    public final List<String> subscriptionLookups = new ArrayList<>();
    public final List<String> chargeLookups = new ArrayList<>();
    public final Map<String, GatewaySubscriptionSnapshot> subscriptions = new HashMap<>();
    public final Map<String, GatewayChargeSnapshot> charges = new HashMap<>();
    public final Map<String, List<GatewayChargeSnapshot>> chargesBySubscription = new HashMap<>();
    /** Falla de las operaciones de escritura (crear, cambiar importe, cancelar). */
    public RuntimeException failure;
    /** Falla de las consultas. */
    public RuntimeException readFailure;
    private int sequence;

    @Override
    public GatewayName name() {
        return GatewayName.MERCADOPAGO;
    }

    @Override
    public RecurringCheckoutResult createRecurringCheckout(RecurringCheckoutRequest request) {
        failIfConfigured();
        created.add(request);
        String id = "pre-" + (++sequence);
        // El resultado es authorized; el snapshot en mapa queda pending hasta que el test (o un
        // h.preapproval AUTHORIZED) simule el webhook / la reconsulta.
        subscriptions.putIfAbsent(id, new GatewaySubscriptionSnapshot(id, GatewaySubscriptionStatus.PENDING,
                "pending", request.checkoutId().toString(), request.amount(), request.currency(), null, null));
        return new RecurringCheckoutResult(id, null, "authorized");
    }

    @Override
    public GatewaySubscriptionSnapshot getSubscription(String gatewaySubscriptionId) {
        failReadIfConfigured();
        subscriptionLookups.add(gatewaySubscriptionId);
        GatewaySubscriptionSnapshot snapshot = subscriptions.get(gatewaySubscriptionId);
        if (snapshot == null) {
            throw new IllegalStateException("preapproval desconocido: " + gatewaySubscriptionId);
        }
        return snapshot;
    }

    @Override
    public GatewayChargeSnapshot getCharge(String gatewayChargeId) {
        failReadIfConfigured();
        chargeLookups.add(gatewayChargeId);
        GatewayChargeSnapshot snapshot = charges.get(gatewayChargeId);
        if (snapshot == null) {
            throw new IllegalStateException("cobro desconocido: " + gatewayChargeId);
        }
        return snapshot;
    }

    @Override
    public List<GatewayChargeSnapshot> listCharges(String gatewaySubscriptionId) {
        failReadIfConfigured();
        return chargesBySubscription.getOrDefault(gatewaySubscriptionId, List.of());
    }

    @Override
    public void updateAmount(String gatewaySubscriptionId, BigDecimal amount) {
        failIfConfigured();
        amountUpdates.add(gatewaySubscriptionId + ":" + amount.toPlainString());
    }

    @Override
    public void cancel(String gatewaySubscriptionId) {
        failIfConfigured();
        canceled.add(gatewaySubscriptionId);
    }

    @Override
    public void pause(String gatewaySubscriptionId) {
        failIfConfigured();
        paused.add(gatewaySubscriptionId);
    }

    @Override
    public void resume(String gatewaySubscriptionId) {
        failIfConfigured();
        resumed.add(gatewaySubscriptionId);
    }

    private void failIfConfigured() {
        if (failure != null) {
            throw failure;
        }
    }

    private void failReadIfConfigured() {
        if (readFailure != null) {
            throw readFailure;
        }
    }
}
