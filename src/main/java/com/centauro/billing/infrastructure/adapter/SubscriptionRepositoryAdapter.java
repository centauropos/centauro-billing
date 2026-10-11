package com.centauro.billing.infrastructure.adapter;

import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.port.SubscriptionRepositoryPort;
import com.centauro.billing.domain.vo.PageQuery;
import com.centauro.billing.domain.vo.PageResult;
import com.centauro.billing.domain.vo.SubscriptionSearchCriteria;
import com.centauro.billing.infrastructure.jpa.entity.SubscriptionEntity;
import com.centauro.billing.infrastructure.jpa.repository.SubscriptionJpaRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class SubscriptionRepositoryAdapter implements SubscriptionRepositoryPort {

    private static final Set<String> SORTABLE = Set.of("createdAt", "trialEndsAt", "currentPeriodEnd",
            "graceEndsAt", "status", "companyName", "tenantId");

    private final SubscriptionJpaRepository repository;

    public SubscriptionRepositoryAdapter(SubscriptionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Subscription> findByTenantId(String tenantId) {
        return repository.findByTenantId(tenantId).map(SubscriptionRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<Subscription> findById(UUID id) {
        return repository.findById(id).map(SubscriptionRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<Subscription> findByGatewaySubscriptionId(String gatewaySubscriptionId) {
        return repository.findFirstByGatewaySubscriptionId(gatewaySubscriptionId)
                .map(SubscriptionRepositoryAdapter::toDomain);
    }

    @Override
    public List<UUID> findWithGatewaySubscription(UUID afterId, int limit) {
        return repository.findWithGatewaySubscription(afterId, limit);
    }

    @Override
    public Optional<Subscription> findByTenantIdForUpdate(String tenantId) {
        return repository.findByTenantIdForUpdate(tenantId).map(SubscriptionRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<Subscription> findByIdForUpdateSkipLocked(UUID id) {
        return repository.findByIdForUpdateSkipLocked(id).map(SubscriptionRepositoryAdapter::toDomain);
    }

    @Override
    public boolean insertIfAbsent(Subscription s) {
        return repository.insertTrialIfAbsent(s.getId(), s.getTenantId(), s.getPlanId(), s.getTrialEndsAt(),
                s.getPriceAmount(), s.getBillingEmail(), s.getCompanyName(), s.getCreatedAt()) > 0;
    }

    @Override
    public Subscription save(Subscription s) {
        SubscriptionEntity entity = repository.findById(s.getId()).orElseGet(() -> {
            SubscriptionEntity created = new SubscriptionEntity();
            created.setId(s.getId());
            created.setTenantId(s.getTenantId());
            created.setCreatedAt(s.getCreatedAt());
            return created;
        });
        if (entity.getVersion() != null && s.getVersion() != null && !entity.getVersion().equals(s.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(SubscriptionEntity.class, s.getId());
        }
        copy(s, entity);
        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    public List<UUID> findTrialsDue(OffsetDateTime now, UUID afterId, int limit) {
        return repository.findTrialsDue(now, afterId, limit);
    }

    @Override
    public List<UUID> findPeriodsDue(OffsetDateTime now, UUID afterId, int limit) {
        return repository.findPeriodsDue(now, afterId, limit);
    }

    @Override
    public List<UUID> findGraceDue(OffsetDateTime now, UUID afterId, int limit) {
        return repository.findGraceDue(now, afterId, limit);
    }

    @Override
    public PageResult<Subscription> search(SubscriptionSearchCriteria criteria, PageQuery pageQuery) {
        String property = SORTABLE.contains(pageQuery.sortProperty()) ? pageQuery.sortProperty() : "createdAt";
        Sort sort = Sort.by(pageQuery.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC, property);
        Page<SubscriptionEntity> page = repository.findAll(specification(criteria),
                PageRequest.of(pageQuery.page(), pageQuery.size(), sort));
        return new PageResult<>(page.getContent().stream().map(SubscriptionRepositoryAdapter::toDomain).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    private static Specification<SubscriptionEntity> specification(SubscriptionSearchCriteria c) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (c.status() != null) {
                predicates.add(cb.equal(root.get("status"), c.status()));
            }
            if (c.planId() != null) {
                predicates.add(cb.equal(root.get("planId"), c.planId()));
            }
            if (c.tenantId() != null) {
                predicates.add(cb.equal(root.get("tenantId"), c.tenantId()));
            }
            if (c.query() != null) {
                String like = "%" + escapeLike(c.query().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("companyName")), like, '\\'),
                        cb.like(cb.lower(root.get("billingEmail")), like, '\\')));
            }
            if (c.trialEndsBefore() != null) {
                predicates.add(cb.lessThan(root.get("trialEndsAt"), c.trialEndsBefore()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static Subscription toDomain(SubscriptionEntity e) {
        return new Subscription(e.getId(), e.getTenantId(), e.getPlanId(), e.getStatus(), e.getSuspensionReason(),
                e.getPastDueReason(), e.getTrialEndsAt(), e.getCurrentPeriodStart(), e.getCurrentPeriodEnd(),
                e.getBillingAnchorDay() == null ? null : e.getBillingAnchorDay().intValue(), e.getGraceEndsAt(), e.isCancelAtPeriodEnd(), e.getCanceledAt(), e.getSuspendedAt(),
                e.getPriceAmount(), e.getCurrency(), e.getGateway(), e.getGatewaySubscriptionId(),
                e.getGatewayStatus(), e.getGatewayLastModified(), e.getNextChargeAt(), e.getBillingEmail(),
                e.getCompanyName(), e.getVersion(), e.getCreatedAt());
    }

    private static void copy(Subscription s, SubscriptionEntity e) {
        e.setPlanId(s.getPlanId());
        e.setStatus(s.getStatus());
        e.setSuspensionReason(s.getSuspensionReason());
        e.setPastDueReason(s.getPastDueReason());
        e.setTrialEndsAt(s.getTrialEndsAt());
        e.setCurrentPeriodStart(s.getCurrentPeriodStart());
        e.setCurrentPeriodEnd(s.getCurrentPeriodEnd());
        e.setBillingAnchorDay(s.getBillingAnchorDay() == null ? null : s.getBillingAnchorDay().shortValue());
        e.setGraceEndsAt(s.getGraceEndsAt());
        e.setCancelAtPeriodEnd(s.isCancelAtPeriodEnd());
        e.setCanceledAt(s.getCanceledAt());
        e.setSuspendedAt(s.getSuspendedAt());
        e.setPriceAmount(s.getPriceAmount());
        e.setCurrency(s.getCurrency());
        e.setGateway(s.getGateway());
        e.setGatewaySubscriptionId(s.getGatewaySubscriptionId());
        e.setGatewayStatus(s.getGatewayStatus());
        e.setGatewayLastModified(s.getGatewayLastModified());
        e.setNextChargeAt(s.getNextChargeAt());
        e.setBillingEmail(s.getBillingEmail());
        e.setCompanyName(s.getCompanyName());
    }
}
