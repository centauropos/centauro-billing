package com.centauro.billing.web.controller;

import com.centauro.billing.application.usecase.WebhookUseCase;
import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.enums.WebhookSignatureResult;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.exception.InvalidWebhookSignatureException;
import com.centauro.billing.domain.exception.WebhookPayloadTooLargeException;
import com.centauro.billing.domain.port.WebhookSignatureVerifier;
import com.centauro.billing.domain.vo.WebhookNotification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Webhook público de Mercado Pago. Orden: {@code data.id} del query obligatorio (400), firma (401), tamaño del
 * cuerpo (413) y cuerpo JSON válido (400); en ninguno de esos casos se guarda nada. Con todo válido responde 200
 * aunque el procesamiento falle (queda FAILED para el job).
 *
 * <p>El recurso es solo el {@code data.id} del query, que es el que cubre la firma: el {@code data.id} del cuerpo
 * no está firmado y se ignora. El estado se reconsulta siempre en MP.
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
    private final int maxBodyBytes;

    public MercadoPagoWebhookController(WebhookSignatureVerifier signatureVerifier, WebhookUseCase webhookUseCase,
                                        ObjectMapper objectMapper,
                                        @Value("${billing.mercadopago.webhook.max-body-bytes:65536}")
                                        int maxBodyBytes) {
        this.signatureVerifier = signatureVerifier;
        this.webhookUseCase = webhookUseCase;
        this.objectMapper = objectMapper;
        this.maxBodyBytes = maxBodyBytes;
    }

    @PostMapping
    public Map<String, Object> receive(HttpServletRequest request,
                                       @RequestParam(name = "data.id", required = false) String dataId,
                                       @RequestParam(name = "type", required = false) String type,
                                       @RequestParam(name = "topic", required = false) String topic,
                                       @RequestHeader(name = "x-signature", required = false) String signature,
                                       @RequestHeader(name = "x-request-id", required = false) String requestId) {
        if (dataId == null || dataId.isBlank()) {
            throw new InvalidBillingRequestException("Falta data.id en la URL del webhook");
        }
        WebhookSignatureResult result = signatureVerifier.verify(signature, requestId, dataId);
        if (!result.isValid()) {
            log.warn("Webhook de Mercado Pago rechazado: firma {} (x-request-id {})", result,
                    requestId == null ? "ausente" : "presente");
            throw new InvalidWebhookSignatureException();
        }
        Map<String, Object> payload = parse(readBody(request));
        Object data = payload.get("data");
        String bodyDataId = data instanceof Map<?, ?> map ? text(map.get("id")) : null;
        if (bodyDataId != null && !bodyDataId.equals(dataId.trim())) {
            log.warn("Webhook de Mercado Pago: el data.id del cuerpo no coincide con el firmado ({}); se usa el "
                    + "firmado", dataId);
        }
        String notificationTopic = firstNonBlank(text(payload.get("type")), type, topic);
        Boolean liveMode = payload.get("live_mode") instanceof Boolean b ? b : null;
        webhookUseCase.receive(new WebhookNotification(GatewayName.MERCADOPAGO, text(payload.get("id")), requestId,
                notificationTopic, text(payload.get("action")), dataId.trim(), liveMode, payload));
        return RECEIVED;
    }

    private String readBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > maxBodyBytes) {
            throw new WebhookPayloadTooLargeException(maxBodyBytes);
        }
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(maxBodyBytes + 1);
            if (bytes.length > maxBodyBytes) {
                throw new WebhookPayloadTooLargeException(maxBodyBytes);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InvalidBillingRequestException("No se pudo leer el cuerpo del webhook");
        }
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
