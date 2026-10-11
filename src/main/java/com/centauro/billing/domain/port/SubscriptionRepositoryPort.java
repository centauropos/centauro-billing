package com.centauro.billing.domain.port;

import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.vo.PageQuery;
import com.centauro.billing.domain.vo.PageResult;
import com.centauro.billing.domain.vo.SubscriptionSearchCriteria;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepositoryPort {

    Optional<Subscription> findByTenantId(String tenantId);

    /** Lectura con bloqueo pesimista para mutaciones desde la API. */
    Optional<Subscription> findByTenantIdForUpdate(String tenantId);

    /** Bloquea la fila si está libre; vacío si no existe o la tiene otra transacción. */
    Optional<Subscription> findByIdForUpdateSkipLocked(UUID id);

    /** {@code INSERT ... ON CONFLICT (tenant_id) DO NOTHING}; devuelve si insertó. */
    boolean insertIfAbsent(Subscription subscription);

    Subscription save(Subscription subscription);

    List<UUID> findTrialsDue(OffsetDateTime now, int limit);

    List<UUID> findPeriodsDue(OffsetDateTime now, int limit);

    List<UUID> findGraceDue(OffsetDateTime now, int limit);

    PageResult<Subscription> search(SubscriptionSearchCriteria criteria, PageQuery pageQuery);
}