package com.centauro.billing.infrastructure.adapter;

import com.centauro.billing.domain.enums.PaymentSource;
import com.centauro.billing.domain.model.Payment;
import com.centauro.billing.domain.port.PaymentRepositoryPort;
import com.centauro.billing.infrastructure.jpa.entity.PaymentEntity;
import com.centauro.billing.infrastructure.jpa.repository.PaymentJpaRepository;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class PaymentRepositoryAdapter implements PaymentRepositoryPort {

    private final PaymentJpaRepository repository;

    public PaymentRepositoryAdapter(PaymentJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Payment save(Payment payment) {
        PaymentEntity entity = repository.findById(payment.id()).orElseGet(PaymentEntity::new);
        entity.setId(payment.id());
        entity.setSubscriptionId(payment.subscriptionId());
        entity.setTenantId(payment.tenantId());
        entity.setSource(payment.source());
        entity.setStatus(payment.status());
        entity.setAmount(payment.amount());
        entity.setCurrency(payment.currency());
        entity.setGatewayPaymentId(payment.gatewayPaymentId());
        entity.setGatewayChargeId(payment.gatewayChargeId());
        entity.setGatewayStatus(payment.gatewayStatus());
        entity.setPeriodStart(payment.periodStart());
        entity.setPeriodEnd(payment.periodEnd());
        entity.setPaidAt(payment.paidAt());
        entity.setApplied(payment.applied());
        entity.setReference(payment.reference());
        entity.setNotes(payment.notes());
        entity.setRegisteredBy(payment.registeredBy());
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(payment.createdAt());
        }
        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    public boolean markApplied(UUID paymentId, OffsetDateTime now) {
        return repository.markApplied(paymentId, now) > 0;
    }

    @Override
    public Optional<Payment> findManualByReference(String tenantId, String reference) {
        return repository.findByTenantIdAndSourceAndReference(tenantId, PaymentSource.MANUAL, reference)
                .map(PaymentRepositoryAdapter::toDomain);
    }

    @Override
    public List<Payment> findBySubscriptionId(UUID subscriptionId) {
        return repository.findBySubscriptionIdOrderByCreatedAtDesc(subscriptionId).stream()
                .map(PaymentRepositoryAdapter::toDomain).toList();
    }

    private static Payment toDomain(PaymentEntity e) {
        return new Payment(e.getId(), e.getSubscriptionId(), e.getTenantId(), e.getSource(), e.getStatus(),
                e.getAmount(), e.getCurrency(), e.getGatewayPaymentId(), e.getGatewayChargeId(), e.getGatewayStatus(),
                e.getPeriodStart(), e.getPeriodEnd(), e.getPaidAt(), e.isApplied(), e.getReference(), e.getNotes(),
                e.getRegisteredBy(), e.getCreatedAt());
    }
}
