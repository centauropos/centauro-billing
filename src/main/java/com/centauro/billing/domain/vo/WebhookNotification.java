package com.centauro.billing.domain.vo;

import com.centauro.billing.domain.enums.GatewayName;

import java.util.Map;

/**
 * Notificación ya verificada (firma válida). Solo aporta ids: el estado real se reconsulta en la pasarela.
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

    public WebhookNotification {
        payload = payload == null ? Map.of() : payload;
    }

    /**
     * Clave de deduplicación: el id de la notificación si viene en el cuerpo; si no, el {@code x-request-id};
     * para el IPN legado sin ninguno de los dos, tópico + recurso. {@code null} si no hay con qué deduplicar.
     */
    public String dedupeKey() {
        if (notificationId != null && !notificationId.isBlank()) {
            return "n:" + notificationId;
        }
        if (requestId != null && !requestId.isBlank()) {
            return "r:" + requestId;
        }
        if (topic != null && resourceId != null) {
            return "t:" + topic + ":" + resourceId;
        }
        return null;
    }
}
