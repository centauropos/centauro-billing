package com.centauro.billing.infrastructure.jpa.entity;

import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.enums.WebhookStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "webhook_events")
@Getter
@Setter
@NoArgsConstructor
public class WebhookEventEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GatewayName provider;

    @Column(name = "dedupe_key", nullable = false, length = 150)
    private String dedupeKey;

    @Column(name = "notification_id", length = 64)
    private String notificationId;

    @Column(name = "request_id", length = 128)
    private String requestId;

    @Column(length = 60)
    private String topic;

    @Column(length = 60)
    private String action;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @Column(name = "live_mode")
    private Boolean liveMode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;
}
