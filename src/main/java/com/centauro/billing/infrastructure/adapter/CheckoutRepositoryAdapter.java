package com.centauro.billing.infrastructure.adapter;

import com.centauro.billing.domain.enums.CheckoutStatus;
import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.model.GatewayCheckout;
import com.centauro.billing.domain.port.CheckoutRepositoryPort;
import com.centauro.billing.infrastructure.jpa.entity.GatewayCheckoutEntity;
import com.centauro.billing.infrastructure.jpa.repository.GatewayCheckoutJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class CheckoutRepositoryAdapter implements CheckoutRepositoryPort {

    private final GatewayCheckoutJpaRepository repository;

    public CheckoutRepositoryAdapter(GatewayCheckoutJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public GatewayCheckout save(GatewayCheckout c) {
        GatewayCheckoutEntity entity = repository.findById(c.getId()).orElseGet(() -> {
            GatewayCheckoutEntity created = new GatewayCheckoutEntity();
            created.setId(c.getId());
            created.setCreatedAt(c.getCreatedAt());
            return created;
        });
        entity.setSubscriptionId(c.getSubscriptionId());
        entity.setTenantId(c.getTenantId());
        entity.setPlanId(c.getPlanId());
        entity.setPriceAmount(c.getPriceAmount());
        entity.setCurrency(c.getCurrency());
        entity.setGateway(c.getGateway());
        entity.setGatewayPreapprovalId(c.getGatewayPreapprovalId());
        entity.setInitPoint(c.getInitPoint());
        entity.setPayerEmail(c.getPayerEmail());
        entity.setStatus(c.getStatus());
        entity.setStartDate(c.getStartDate());
        entity.setExpiresAt(c.getExpiresAt());
        entity.setRequestedBy(c.getRequestedBy());
        entity.setUpdatedAt(c.getUpdatedAt() != null ? c.getUpdatedAt() : c.getCreatedAt());
        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    public Optional<GatewayCheckout> findById(UUID id) {
        return repository.findById(id).map(CheckoutRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<GatewayCheckout> findByIdForUpdate(UUID id) {
        return repository.findByIdForUpdate(id).map(CheckoutRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<GatewayCheckout> findByIdForUpdateSkipLocked(UUID id) {
        return repository.findByIdForUpdateSkipLocked(id).map(CheckoutRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<GatewayCheckout> findByGatewayPreapprovalId(String preapprovalId) {
        return repository.findByGatewayAndGatewayPreapprovalId(GatewayName.MERCADOPAGO, preapprovalId)
                .map(CheckoutRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<GatewayCheckout> findPendingBySubscriptionId(UUID subscriptionId) {
        return repository.findFirstBySubscriptionIdAndStatus(subscriptionId, CheckoutStatus.PENDING)
                .map(CheckoutRepositoryAdapter::toDomain);
    }

    @Override
    public List<GatewayCheckout> findRecentBySubscriptionId(UUID subscriptionId, int limit) {
        return repository.findBySubscriptionIdOrderByCreatedAtDesc(subscriptionId, PageRequest.of(0, limit))
                .stream().map(CheckoutRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<UUID> findExpiredPending(OffsetDateTime now, UUID afterId, int limit) {
        return repository.findExpiredPending(now, afterId, limit);
    }

    @Override
    public List<UUID> findStaleWithPreapproval(OffsetDateTime since, UUID afterId, int limit) {
        return repository.findStaleWithPreapproval(since, afterId, limit);
    }

    private static GatewayCheckout toDomain(GatewayCheckoutEntity e) {
        return new GatewayCheckout(e.getId(), e.getSubscriptionId(), e.getTenantId(), e.getPlanId(),
                e.getPriceAmount(), e.getCurrency(), e.getGateway(), e.getGatewayPreapprovalId(), e.getInitPoint(),
                e.getPayerEmail(), e.getStatus(), e.getStartDate(), e.getExpiresAt(), e.getRequestedBy(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
