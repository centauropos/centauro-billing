package com.centauro.billing.infrastructure.adapter;

import com.centauro.billing.domain.enums.WebhookStatus;
import com.centauro.billing.domain.model.WebhookEvent;
import com.centauro.billing.domain.port.WebhookEventRepositoryPort;
import com.centauro.billing.infrastructure.jpa.entity.WebhookEventEntity;
import com.centauro.billing.infrastructure.jpa.repository.WebhookEventJpaRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Las escrituras van en una transacción propia ({@code REQUIRES_NEW}): un error acá no tiene que marcar para
 * rollback una transacción del llamador. {@code dedupe_key}, {@code request_id} y {@code resource_id} llegan ya
 * validados contra el largo de la columna ({@code WebhookUseCase}); los campos del cuerpo, que no están firmados,
 * se recortan.
 */
@Component
public class WebhookEventRepositoryAdapter implements WebhookEventRepositoryPort {

    private final WebhookEventJpaRepository repository;
    private final ObjectMapper objectMapper;

    public WebhookEventRepositoryAdapter(WebhookEventJpaRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean insertIfAbsent(WebhookEvent e) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(e.getPayload());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Payload de webhook no serializable", ex);
        }
        return repository.insertIfAbsent(e.getId(), e.getProvider().name(), e.getDedupeKey(),
                cut(e.getNotificationId(), 64), e.getRequestId(), cut(e.getTopic(), 60),
                cut(e.getAction(), 60), e.getResourceId(), e.getLiveMode(), payload, e.getNextAttemptAt(),
                e.getReceivedAt()) > 0;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean update(WebhookEvent e, WebhookStatus expectedStatus, int expectedAttempts) {
        return repository.updateIfUnchanged(e.getId(), e.getStatus().name(), e.getAttempts(),
                e.getLastError(), e.getNextAttemptAt(), e.getProcessedAt(),
                expectedStatus.name(), expectedAttempts) > 0;
    }

    @Override
    public Optional<WebhookEvent> findById(UUID id) {
        return repository.findById(id).map(WebhookEventRepositoryAdapter::toDomain);
    }

    @Override
    public List<UUID> findRetryable(OffsetDateTime now, int maxAttempts, UUID afterId, int limit) {
        return repository.findRetryable(now, maxAttempts, afterId, limit);
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static WebhookEvent toDomain(WebhookEventEntity e) {
        return new WebhookEvent(e.getId(), e.getProvider(), e.getDedupeKey(), e.getNotificationId(),
                e.getRequestId(), e.getTopic(), e.getAction(), e.getResourceId(), e.getLiveMode(), e.getPayload(),
                e.getStatus(), e.getAttempts(), e.getLastError(), e.getNextAttemptAt(), e.getReceivedAt(),
                e.getProcessedAt());
    }
}
