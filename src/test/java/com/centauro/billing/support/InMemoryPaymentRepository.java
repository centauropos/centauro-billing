package com.centauro.billing.support;

import com.centauro.billing.domain.enums.PaymentSource;
import com.centauro.billing.domain.model.Payment;
import com.centauro.billing.domain.port.PaymentRepositoryPort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class InMemoryPaymentRepository implements PaymentRepositoryPort {

    public final Map<UUID, Payment> payments = new LinkedHashMap<>();

    @Override
    public Payment save(Payment payment) {
        payments.put(payment.id(), payment);
        return payment;
    }

    @Override
    public boolean markApplied(UUID paymentId) {
        Payment current = payments.get(paymentId);
        if (current == null || current.applied()) {
            return false;
        }
        payments.put(paymentId, current.markApplied());
        return true;
    }

    @Override
    public boolean existsManualReference(String tenantId, String reference) {
        return payments.values().stream().anyMatch(p -> p.source() == PaymentSource.MANUAL
                && p.tenantId().equals(tenantId) && reference.equals(p.reference()));
    }

    @Override
    public List<Payment> findBySubscriptionId(UUID subscriptionId) {
        return payments.values().stream().filter(p -> p.subscriptionId().equals(subscriptionId)).toList();
    }
}