package com.centauro.billing.domain.port;

import com.centauro.billing.domain.model.Payment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepositoryPort {

    Payment save(Payment payment);

    /**
     * {@code UPDATE payments SET applied = true, updated_at = now WHERE id = ? AND applied = false};
     * devuelve si actualizó.
     */
    boolean markApplied(UUID paymentId, OffsetDateTime now);

    Optional<Payment> findManualByReference(String tenantId, String reference);

    List<Payment> findBySubscriptionId(UUID subscriptionId);
}
