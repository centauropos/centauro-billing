package com.centauro.billing.infrastructure.jpa.repository;

import com.centauro.billing.infrastructure.jpa.entity.WebhookEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface WebhookEventJpaRepository extends JpaRepository<WebhookEventEntity, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO webhook_events (id, provider, dedupe_key, notification_id, request_id, topic, action,
                                        resource_id, live_mode, payload, status, attempts, next_attempt_at,
                                        received_at)
            VALUES (:id, :provider, :dedupeKey, CAST(:notificationId AS VARCHAR), CAST(:requestId AS VARCHAR),
                    CAST(:topic AS VARCHAR), CAST(:action AS VARCHAR), CAST(:resourceId AS VARCHAR),
                    CAST(:liveMode AS BOOLEAN), CAST(:payload AS JSONB), 'RECEIVED', 0, :nextAttemptAt,
                    :receivedAt)
            ON CONFLICT (provider, dedupe_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("provider") String provider,
                       @Param("dedupeKey") String dedupeKey,
                       @Param("notificationId") String notificationId,
                       @Param("requestId") String requestId,
                       @Param("topic") String topic,
                       @Param("action") String action,
                       @Param("resourceId") String resourceId,
                       @Param("liveMode") Boolean liveMode,
                       @Param("payload") String payload,
                       @Param("nextAttemptAt") OffsetDateTime nextAttemptAt,
                       @Param("receivedAt") OffsetDateTime receivedAt);

    @Query(value = """
            SELECT id FROM webhook_events
            WHERE status IN ('RECEIVED', 'FAILED') AND next_attempt_at <= :now AND attempts < :maxAttempts
              AND id > :afterId
            ORDER BY id
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findRetryable(@Param("now") OffsetDateTime now, @Param("maxAttempts") int maxAttempts,
                             @Param("afterId") UUID afterId, @Param("limit") int limit);
}
