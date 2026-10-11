package com.centauro.billing.domain.port;

import com.centauro.billing.domain.model.GatewayCheckout;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckoutRepositoryPort {

    GatewayCheckout save(GatewayCheckout checkout);

    Optional<GatewayCheckout> findById(UUID id);

    /** Con bloqueo pesimista: el webhook y el job de expiración no pisan el mismo checkout. */
    Optional<GatewayCheckout> findByIdForUpdate(UUID id);

    /** Bloquea la fila si está libre; vacío si no existe o la tiene otra transacción. */
    Optional<GatewayCheckout> findByIdForUpdateSkipLocked(UUID id);

    Optional<GatewayCheckout> findByGatewayPreapprovalId(String preapprovalId);

    /** El checkout PENDING de la suscripción, si hay (a lo sumo uno: {@code uq_checkouts_one_pending}). */
    Optional<GatewayCheckout> findPendingBySubscriptionId(UUID subscriptionId);

    List<GatewayCheckout> findRecentBySubscriptionId(UUID subscriptionId, int limit);

    /** PENDING con {@code expires_at <= now}, con cursor por id. */
    List<UUID> findExpiredPending(OffsetDateTime now, UUID afterId, int limit);

    /**
     * Checkouts que ya no son el vigente (SUPERSEDED, EXPIRED, CANCELLED) pero tienen preapproval y se
     * tocaron después de {@code since}: la conciliación verifica que no hayan quedado autorizados en la pasarela.
     */
    List<UUID> findStaleWithPreapproval(OffsetDateTime since, UUID afterId, int limit);
}
