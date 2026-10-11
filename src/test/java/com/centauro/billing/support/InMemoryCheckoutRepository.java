package com.centauro.billing.support;

import com.centauro.billing.domain.enums.CheckoutStatus;
import com.centauro.billing.domain.model.GatewayCheckout;
import com.centauro.billing.domain.port.CheckoutRepositoryPort;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class InMemoryCheckoutRepository implements CheckoutRepositoryPort {

    private final Map<UUID, GatewayCheckout> byId = new LinkedHashMap<>();
    public final Set<UUID> lockedByOthers = ConcurrentHashMap.newKeySet();

    public List<GatewayCheckout> all() {
        return List.copyOf(byId.values());
    }

    @Override
    public GatewayCheckout save(GatewayCheckout checkout) {
        if (checkout.isPending() && byId.values().stream().anyMatch(c -> c.isPending()
                && c.getSubscriptionId().equals(checkout.getSubscriptionId()) && !c.getId().equals(checkout.getId()))) {
            throw new IllegalStateException("uq_checkouts_one_pending");
        }
        byId.put(checkout.getId(), checkout);
        return checkout;
    }

    @Override
    public Optional<GatewayCheckout> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<GatewayCheckout> findByIdForUpdate(UUID id) {
        return findById(id);
    }

    @Override
    public Optional<GatewayCheckout> findByIdForUpdateSkipLocked(UUID id) {
        return lockedByOthers.contains(id) ? Optional.empty() : findById(id);
    }

    @Override
    public Optional<GatewayCheckout> findByGatewayPreapprovalId(String preapprovalId) {
        return byId.values().stream().filter(c -> preapprovalId.equals(c.getGatewayPreapprovalId())).findFirst();
    }

    @Override
    public Optional<GatewayCheckout> findPendingBySubscriptionId(UUID subscriptionId) {
        return byId.values().stream()
                .filter(c -> c.isPending() && c.getSubscriptionId().equals(subscriptionId)).findFirst();
    }

    @Override
    public List<GatewayCheckout> findRecentBySubscriptionId(UUID subscriptionId, int limit) {
        return byId.values().stream().filter(c -> c.getSubscriptionId().equals(subscriptionId))
                .sorted(Comparator.comparing(GatewayCheckout::getCreatedAt).reversed()).limit(limit).toList();
    }

    @Override
    public List<UUID> findExpiredPending(OffsetDateTime now, UUID afterId, int limit) {
        return ids(c -> c.isPending() && c.isExpired(now), afterId, limit);
    }

    @Override
    public List<UUID> findStaleWithPreapproval(OffsetDateTime since, UUID afterId, int limit) {
        Set<CheckoutStatus> stale = EnumSet.of(CheckoutStatus.SUPERSEDED, CheckoutStatus.EXPIRED,
                CheckoutStatus.CANCELLED);
        return ids(c -> stale.contains(c.getStatus()) && c.getGatewayPreapprovalId() != null
                && !c.getUpdatedAt().isBefore(since), afterId, limit);
    }

    private List<UUID> ids(Predicate<GatewayCheckout> filter, UUID afterId, int limit) {
        return byId.values().stream().filter(filter).map(GatewayCheckout::getId)
                .filter(id -> id.toString().compareTo(afterId.toString()) > 0)
                .sorted(Comparator.comparing(UUID::toString))
                .limit(limit).toList();
    }
}
