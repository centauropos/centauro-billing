package com.centauro.billing.web.controller;

import com.centauro.billing.application.usecase.WebhookUseCase;
import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.enums.WebhookSignatureResult;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.exception.InvalidWebhookSignatureException;
import com.centauro.billing.domain.port.WebhookSignatureVerifier;
import com.centauro.billing.domain.vo.WebhookNotification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Webhook público de Mercado Pago. La firma se verifica antes de parsear el cuerpo: sin firma válida es 401 y no
 * se guarda nada. Con firma válida responde 200 aunque el procesamiento falle (queda FAILED para el job). Los ids
 * que se procesan son solo referencias: el estado se reconsulta siempre en MP.
 */
@RestController
@RequestMapping("/webhooks/mercadopago")
@Tag(name = "Webhooks")
public class MercadoPagoWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoWebhookController.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final Map<String, Object> RECEIVED = Map.of("received", true);

    private final WebhookSignatureVerifier signatureVerifier;
    private final WebhookUseCase webhookUseCase;
    private final ObjectMapper objectMapper;

    public MercadoPagoWebhookController(WebhookSignatureVerifier signatureVerifier, WebhookUseCase webhookUseCase,
                                        ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.webhookUseCase = webhookUseCase;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public Map<String, Object> receive(@RequestBody(required = false) String body,
                                       @RequestParam(name = "data.id", required = false) String dataId,
                                       @RequestParam(name = "type", required = false) String type,
                                       @RequestParam(name = "topic", required = false) String topic,
                                       @RequestParam(name = "id", required = false) String legacyId,
                                       @RequestHeader(name = "x-signature", required = false) String signature,
                                       @RequestHeader(name = "x-request-id", required = false) String requestId) {
        WebhookSignatureResult result = signatureVerifier.verify(signature, requestId, dataId);
        if (!result.isValid()) {
            log.warn("Webhook de Mercado Pago rechazado: firma {} (x-request-id {})", result,
                    requestId == null ? "ausente" : "presente");
            throw new InvalidWebhookSignatureException();
        }
        Map<String, Object> payload = parse(body);
        Object data = payload.get("data");
        String bodyDataId = data instanceof Map<?, ?> map ? text(map.get("id")) : null;
        String resourceId = firstNonBlank(dataId, bodyDataId, legacyId);
        String notificationTopic = firstNonBlank(text(payload.get("type")), type, topic);
        Boolean liveMode = payload.get("live_mode") instanceof Boolean b ? b : null;
        webhookUseCase.receive(new WebhookNotification(GatewayName.MERCADOPAGO, text(payload.get("id")), requestId,
                notificationTopic, text(payload.get("action")), resourceId, liveMode, payload));
        return RECEIVED;
    }

    private Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, MAP);
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException e) {
            throw new InvalidBillingRequestException("Cuerpo del webhook inválido");
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
