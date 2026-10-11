package com.centauro.billing.support;

import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.port.PaymentGateway;
import com.centauro.billing.domain.vo.GatewayChargeSnapshot;
import com.centauro.billing.domain.vo.GatewaySubscriptionSnapshot;
import com.centauro.billing.domain.vo.RecurringCheckoutRequest;
import com.centauro.billing.domain.vo.RecurringCheckoutResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Pasarela falsa para tests: registra las llamadas y nunca sale a la red. */
public class FakePaymentGateway implements PaymentGateway {

    public final List<String> canceled = new ArrayList<>();
    public final List<String> amountUpdates = new ArrayList<>();
    public RuntimeException failure;

    @Override
    public GatewayName name() {
        return GatewayName.MERCADOPAGO;
    }

    @Override
    public RecurringCheckoutResult createRecurringCheckout(RecurringCheckoutRequest request) {
        throw new UnsupportedOperationException("PR1b");
    }

    @Override
    public GatewaySubscriptionSnapshot getSubscription(String gatewaySubscriptionId) {
        throw new UnsupportedOperationException("PR1b");
    }

    @Override
    public GatewayChargeSnapshot getCharge(String gatewayChargeId) {
        throw new UnsupportedOperationException("PR1b");
    }

    @Override
    public List<GatewayChargeSnapshot> listCharges(String gatewaySubscriptionId) {
        throw new UnsupportedOperationException("PR1b");
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

    private void failIfConfigured() {
        if (failure != null) {
            throw failure;
        }
    }
}