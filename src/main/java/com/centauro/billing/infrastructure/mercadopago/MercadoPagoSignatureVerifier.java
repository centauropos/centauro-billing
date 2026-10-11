package com.centauro.billing.infrastructure.mercadopago;

import com.centauro.billing.domain.enums.WebhookSignatureResult;
import com.centauro.billing.domain.port.WebhookSignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Firma {@code x-signature: ts=<ts>,v1=<hmac>} de Mercado Pago: HMAC-SHA256 en hex con {@code MP_WEBHOOK_SECRET}
 * sobre el manifiesto {@code id:<data.id>;request-id:<x-request-id>;ts:<ts>;}. Según la doc de MP, el segmento
 * de un valor ausente se omite y un {@code data.id} alfanumérico va en minúsculas (verificar).
 *
 * <p>No depende del access token: el bean existe siempre y, sin secreto, el webhook responde 401 a todo.
 */
@Component
public class MercadoPagoSignatureVerifier implements WebhookSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoSignatureVerifier.class);
    private static final String HMAC = "HmacSHA256";
    /** Un {@code ts} mayor a esto está en milisegundos (verificar la unidad contra MP). */
    private static final long MILLIS_THRESHOLD = 100_000_000_000L;

    private final MercadoPagoProperties properties;
    private final Clock clock;

    public MercadoPagoSignatureVerifier(MercadoPagoProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        if (properties.hasAccessToken() && !properties.hasWebhookSecret() && properties.webhook().requireSignature()) {
            log.warn("Mercado Pago: MP_ACCESS_TOKEN configurado sin MP_WEBHOOK_SECRET; el webhook va a rechazar "
                    + "todas las notificaciones con 401");
        }
        if (!properties.webhook().requireSignature()) {
            log.warn("Mercado Pago: MP_WEBHOOK_REQUIRE_SIGNATURE=false; se aceptan notificaciones sin firma");
        }
    }

    @Override
    public WebhookSignatureResult verify(String signatureHeader, String requestId, String dataId) {
        boolean absent = signatureHeader == null || signatureHeader.isBlank();
        if (absent && !properties.webhook().requireSignature()) {
            return WebhookSignatureResult.VALID;
        }
        if (!properties.hasWebhookSecret()) {
            return WebhookSignatureResult.MISSING_SECRET;
        }
        if (absent) {
            return WebhookSignatureResult.MALFORMED;
        }
        String ts = null;
        String v1 = null;
        for (String part : signatureHeader.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if ("ts".equals(key)) {
                ts = value;
            } else if ("v1".equals(key)) {
                v1 = value;
            }
        }
        if (ts == null || v1 == null || ts.isEmpty() || v1.isEmpty() || !ts.chars().allMatch(Character::isDigit)
                || ts.length() > 18) {
            return WebhookSignatureResult.MALFORMED;
        }
        byte[] expected = hmacHex(manifest(dataId, requestId, ts)).getBytes(StandardCharsets.US_ASCII);
        byte[] received = v1.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, received)) {
            return WebhookSignatureResult.INVALID;
        }
        long maxSkew = properties.webhook().maxSkewSeconds();
        if (maxSkew > 0) {
            long value = Long.parseLong(ts);
            long seconds = value > MILLIS_THRESHOLD ? value / 1000 : value;
            long skew = Math.abs(Instant.now(clock).getEpochSecond() - seconds);
            if (skew > maxSkew) {
                return WebhookSignatureResult.EXPIRED;
            }
        }
        return WebhookSignatureResult.VALID;
    }

    static String manifest(String dataId, String requestId, String ts) {
        StringBuilder manifest = new StringBuilder();
        if (dataId != null && !dataId.isBlank()) {
            String id = dataId.trim();
            if (id.chars().allMatch(Character::isLetterOrDigit)) {
                id = id.toLowerCase(Locale.ROOT);
            }
            manifest.append("id:").append(id).append(';');
        }
        if (requestId != null && !requestId.isBlank()) {
            manifest.append("request-id:").append(requestId.trim()).append(';');
        }
        manifest.append("ts:").append(ts).append(';');
        return manifest.toString();
    }

    private String hmacHex(String manifest) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(properties.webhookSecret().getBytes(StandardCharsets.UTF_8), HMAC));
            return HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 no disponible", e);
        }
    }
}
