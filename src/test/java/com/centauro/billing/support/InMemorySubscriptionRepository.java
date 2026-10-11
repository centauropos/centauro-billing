package com.centauro.billing.support;

import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.port.SubscriptionRepositoryPort;
import com.centauro.billing.domain.vo.PageQuery;
import com.centauro.billing.domain.vo.PageResult;
import com.centauro.billing.domain.vo.SubscriptionSearchCriteria;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class InMemorySubscriptionRepository implements SubscriptionRepositoryPort {

    private final Map<UUID, Subscription> byId = new LinkedHashMap<>();
    public final Set<UUID> lockedByOthers = ConcurrentHashMap.newKeySet();
    public int saves;

    public void put(Subscription subscription) {
        byId.put(subscription.getId(), subscription);
    }

    public List<Subscription> all() {
        return List.copyOf(byId.values());
    }

    @Override
    public Optional<Subscription> findByTenantId(String tenantId) {
        return byId.values().stream().filter(s -> s.getTenantId().equals(tenantId)).findFirst();
    }

    @Override
    public Optional<Subscription> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Subscription> findByGatewaySubscriptionId(String gatewaySubscriptionId) {
        return byId.values().stream()
                .filter(s -> gatewaySubscriptionId.equals(s.getGatewaySubscriptionId())).findFirst();
    }

    @Override
    public List<UUID> findWithGatewaySubscription(UUID afterId, int limit) {
        return due(s -> s.getGatewaySubscriptionId() != null, afterId, limit);
    }

    @Override
    public Optional<Subscription> findByTenantIdForUpdate(String tenantId) {
        return findByTenantId(tenantId);
    }

    @Override
    public Optional<Subscription> findByIdForUpdateSkipLocked(UUID id) {
        if (lockedByOthers.contains(id)) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public boolean insertIfAbsent(Subscription subscription) {
        if (findByTenantId(subscription.getTenantId()).isPresent()) {
            return false;
        }
        byId.put(subscription.getId(), subscription);
        return true;
    }

    @Override
    public Subscription save(Subscription subscription) {
        saves++;
        byId.put(subscription.getId(), subscription);
        return subscription;
    }

    @Override
    public List<UUID> findTrialsDue(OffsetDateTime now, UUID afterId, int limit) {
        return due(s -> s.getStatus() == SubscriptionStatus.TRIALING && !s.getTrialEndsAt().isAfter(now),
                afterId, limit);
    }

    @Override
    public List<UUID> findPeriodsDue(OffsetDateTime now, UUID afterId, int limit) {
        return due(s -> s.getStatus() == SubscriptionStatus.ACTIVE && !s.getCurrentPeriodEnd().isAfter(now),
                afterId, limit);
    }

    @Override
    public List<UUID> findGraceDue(OffsetDateTime now, UUID afterId, int limit) {
        return due(s -> s.getStatus() == SubscriptionStatus.PAST_DUE && !s.getGraceEndsAt().isAfter(now),
                afterId, limit);
    }

    @Override
    public PageResult<Subscription> search(SubscriptionSearchCriteria criteria, PageQuery pageQuery) {
        List<Subscription> content = byId.values().stream()
                .filter(s -> criteria.status() == null || s.getStatus() == criteria.status())
                .filter(s -> criteria.planId() == null || s.getPlanId().equals(criteria.planId()))
                .toList();
        return new PageResult<>(content, pageQuery.page(), pageQuery.size(), content.size(), 1);
    }

    /** Mismo orden que PostgreSQL sobre uuid: lexicográfico del texto (no el compareTo con signo de Java). */
    private List<UUID> due(Predicate<Subscription> filter, UUID afterId, int limit) {
        return byId.values().stream().filter(filter).map(Subscription::getId)
                .filter(id -> id.toString().compareTo(afterId.toString()) > 0)
                .sorted(Comparator.comparing(UUID::toString))
                .limit(limit).toList();
    }
}