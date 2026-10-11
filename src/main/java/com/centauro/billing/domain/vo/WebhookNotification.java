package com.centauro.billing.domain.vo;

import com.centauro.billing.domain.enums.GatewayName;

import java.util.Map;

/**
 * Notificación ya verificada (firma válida). Solo aporta ids: el estado real se reconsulta en la pasarela.
 * {@code resourceId} es el {@code data.id} del query string (el que cubre la firma), nunca el del cuerpo.
 * {@code payload} es el cuerpo recibido, que se guarda tal cual en {@code webhook_events}.
 */
public record WebhookNotification(
        GatewayName provider,
        String notificationId,
        String requestId,
        String topic,
        String action,
        String resourceId,
        Boolean liveMode,
        Map<String, Object> payload
) {

    /** Largos de {@code webhook_events}: un valor más largo se rechaza con 400, no se recorta. */
    public static final int MAX_DEDUPE_KEY_LENGTH = 150;
    public static final int MAX_REQUEST_ID_LENGTH = 128;
    public static final int MAX_RESOURCE_ID_LENGTH = 64;

    public WebhookNotification {
        payload = payload == null ? Map.of() : payload;
    }

    /**
     * Clave de deduplicación: {@code x-request-id} + {@code data.id}, los dos valores que cubre la firma (el cuerpo
     * no está firmado). {@code null} si falta alguno.
     */
    public String dedupeKey() {
        if (requestId == null || requestId.isBlank() || resourceId == null || resourceId.isBlank()) {
            return null;
        }
        return "r:" + requestId.trim() + ":d:" + resourceId.trim();
    }
}
