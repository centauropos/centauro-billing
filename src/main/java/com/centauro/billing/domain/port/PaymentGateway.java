package com.centauro.billing.domain.port;

import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.vo.GatewayChargeSnapshot;
import com.centauro.billing.domain.vo.GatewaySubscriptionSnapshot;
import com.centauro.billing.domain.vo.RecurringCheckoutRequest;
import com.centauro.billing.domain.vo.RecurringCheckoutResult;

import java.math.BigDecimal;
import java.util.List;

public interface PaymentGateway {

    GatewayName name();

    RecurringCheckoutResult createRecurringCheckout(RecurringCheckoutRequest request);

    GatewaySubscriptionSnapshot getSubscription(String gatewaySubscriptionId);

    GatewayChargeSnapshot getCharge(String gatewayChargeId);

    List<GatewayChargeSnapshot> listCharges(String gatewaySubscriptionId);

    void updateAmount(String gatewaySubscriptionId, BigDecimal amount);

    void cancel(String gatewaySubscriptionId);
}