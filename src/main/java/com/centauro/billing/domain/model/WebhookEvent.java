package com.centauro.billing.domain.model;

import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.enums.WebhookStatus;
import com.centauro.billing.domain.vo.WebhookNotification;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Notificación recibida y guardada antes de procesarla. Se procesa (y reprocesa) de forma idempotente. */
public class WebhookEvent {

    /** Si el proceso muere entre el insert y el procesamiento, el job lo retoma pasado este margen. */
    public static final Duration FIRST_RETRY_DELAY = Duration.ofMinutes(5);
    static final Duration MAX_BACKOFF = Duration.ofHours(6);
    static final int MAX_ERROR_LENGTH = 500;

    private final UUID id;
    private final GatewayName provider;
    private final String dedupeKey;
    private final String notificationId;
    private final String requestId;
    private final String topic;
    private final String action;
    private final String resourceId;
    private final Boolean liveMode;
    private final Map<String, Object> payload;
    private WebhookStatus status;
    private int attempts;
    private String lastError;
    private OffsetDateTime nextAttemptAt;
    private final OffsetDateTime receivedAt;
    private OffsetDateTime processedAt;

    public WebhookEvent(UUID id, GatewayName provider, String dedupeKey, String notificationId, String requestId,
                        String topic, String action, String resourceId, Boolean liveMode,
                        Map<String, Object> payload, WebhookStatus status, int attempts, String lastError,
                        OffsetDateTime nextAttemptAt, OffsetDateTime receivedAt, OffsetDateTime processedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.dedupeKey = Objects.requireNonNull(dedupeKey, "dedupeKey");
        this.notificationId = notificationId;
        this.requestId = requestId;
        this.topic = topic;
        this.action = action;
        this.resourceId = resourceId;
        this.liveMode = liveMode;
        this.payload = payload == null ? Map.of() : payload;
        this.status = Objects.requireNonNull(status, "status");
        this.attempts = attempts;
        this.lastError = lastError;
        this.nextAttemptAt = nextAttemptAt;
        this.receivedAt = receivedAt;
        this.processedAt = processedAt;
    }

    public static WebhookEvent received(WebhookNotification notification, OffsetDateTime now) {
        String dedupeKey = Objects.requireNonNull(notification.dedupeKey(), "dedupeKey");
        return new WebhookEvent(UUID.randomUUID(), notification.provider(), dedupeKey,
                notification.notificationId(), notification.requestId(), notification.topic(),
                notification.action(), notification.resourceId(), notification.liveMode(), notification.payload(),
                WebhookStatus.RECEIVED, 0, null, now.plus(FIRST_RETRY_DELAY), now, null);
    }

    public void markProcessed(String detail, OffsetDateTime now) {
        finish(WebhookStatus.PROCESSED, detail, now);
    }

    public void markIgnored(String detail, OffsetDateTime now) {
        finish(WebhookStatus.IGNORED, detail, now);
    }

    private void finish(WebhookStatus target, String detail, OffsetDateTime now) {
        status = target;
        lastError = truncate(detail);
        nextAttemptAt = null;
        processedAt = now;
    }

    /** Suma un intento y agenda el próximo con backoff exponencial ({@code 2^intentos} minutos, tope 6 h). */
    public void markFailed(String error, OffsetDateTime now) {
        status = WebhookStatus.FAILED;
        attempts++;
        lastError = truncate(error);
        nextAttemptAt = now.plus(backoff(attempts));
    }

    public static Duration backoff(int attempts) {
        if (attempts >= 20) {
            return MAX_BACKOFF;
        }
        Duration delay = Duration.ofMinutes(1L << Math.max(0, attempts));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public GatewayName getProvider() {
        return provider;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public String getNotificationId() {
        return notificationId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTopic() {
        return topic;
    }

    public String getAction() {
        return action;
    }

    public String getResourceId() {
        return resourceId;
    }

    public Boolean getLiveMode() {
        return liveMode;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public WebhookStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public OffsetDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public OffsetDateTime getReceivedAt() {
        return receivedAt;
    }

    public OffsetDateTime getProcessedAt() {
        return processedAt;
    }
}
