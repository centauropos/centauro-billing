package com.centauro.billing.domain.port;

import com.centauro.billing.domain.enums.WebhookStatus;
import com.centauro.billing.domain.model.WebhookEvent;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookEventRepositoryPort {

    /**
     * {@code INSERT ... ON CONFLICT (provider, dedupe_key) DO NOTHING} en su propia transacción.
     * Devuelve false si la notificación ya estaba guardada.
     */
    boolean insertIfAbsent(WebhookEvent event);

    /**
     * Persiste estado, intentos y error en su propia transacción, solo si la fila sigue con
     * {@code expectedStatus} y {@code expectedAttempts} (lo que se leyó antes de procesar): el webhook y el job de
     * reintentos no se pisan. Devuelve false si otro proceso la actualizó antes.
     */
    boolean update(WebhookEvent event, WebhookStatus expectedStatus, int expectedAttempts);

    Optional<WebhookEvent> findById(UUID id);

    /** RECEIVED o FAILED con {@code next_attempt_at <= now} y menos de {@code maxAttempts} intentos, por id. */
    List<UUID> findRetryable(OffsetDateTime now, int maxAttempts, UUID afterId, int limit);
}
