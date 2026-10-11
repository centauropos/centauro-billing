package com.centauro.billing.domain.port;

import com.centauro.billing.domain.model.Payment;

import java.util.List;
import java.util.UUID;

public interface PaymentRepositoryPort {

    Payment save(Payment payment);

    /** {@code UPDATE payments SET applied = true WHERE id = ? AND applied = false}; devuelve si actualizó. */
    boolean markApplied(UUID paymentId);

    boolean existsManualReference(String tenantId, String reference);

    List<Payment> findBySubscriptionId(UUID subscriptionId);
}