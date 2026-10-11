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
 * <p>Con secreto configurado la firma es siempre obligatoria y {@code require-signature} se ignora. Sin secreto:
 * con {@code require-signature=true} (default) el webhook responde 401 a todo; con {@code false} acepta sin
 * verificar (solo desarrollo), y la aplicación no arranca si el access token es de producción ({@code APP_USR-}).
 *
 * <p>Antigüedad: si {@code max-skew-seconds > 0}, un {@code ts} fuera de esa ventana es {@code EXPIRED}. No está
 * confirmado si MP manda el {@code ts} en segundos o en milisegundos: un valor mayor a 10^12 se toma como
 * milisegundos (10^12 segundos es el año 33658; 10^12 milisegundos, septiembre de 2001).
 */
@Component
public class MercadoPagoSignatureVerifier implements WebhookSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoSignatureVerifier.class);
    private static final String HMAC = "HmacSHA256";
    static final long MILLIS_THRESHOLD = 1_000_000_000_000L;

    private final MercadoPagoProperties properties;
    private final Clock clock;

    public MercadoPagoSignatureVerifier(MercadoPagoProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        boolean requireSignature = properties.webhook().requireSignature();
        if (!requireSignature && properties.isProductionToken()) {
            throw new IllegalStateException("MP_WEBHOOK_REQUIRE_SIGNATURE=false no se admite con un access token "
                    + "de producción de Mercado Pago");
        }
        if (properties.hasWebhookSecret()) {
            if (!requireSignature) {
                log.warn("Mercado Pago: MP_WEBHOOK_SECRET configurado; se ignora MP_WEBHOOK_REQUIRE_SIGNATURE=false "
                        + "y la firma es obligatoria");
            }
        } else if (requireSignature) {
            if (properties.hasAccessToken()) {
                log.warn("Mercado Pago: MP_ACCESS_TOKEN configurado sin MP_WEBHOOK_SECRET; el webhook va a rechazar "
                        + "todas las notificaciones con 401");
            }
        } else {
            log.warn("Mercado Pago: sin MP_WEBHOOK_SECRET y MP_WEBHOOK_REQUIRE_SIGNATURE=false; se aceptan "
                    + "notificaciones sin firma (solo desarrollo)");
        }
    }

    @Override
    public WebhookSignatureResult verify(String signatureHeader, String requestId, String dataId) {
        if (!properties.hasWebhookSecret()) {
            return properties.webhook().requireSignature()
                    ? WebhookSignatureResult.MISSING_SECRET : WebhookSignatureResult.VALID;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
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
            long skew = Math.abs(Instant.now(clock).getEpochSecond() - epochSeconds(Long.parseLong(ts)));
            if (skew > maxSkew) {
                return WebhookSignatureResult.EXPIRED;
            }
        }
        return WebhookSignatureResult.VALID;
    }

    static long epochSeconds(long ts) {
        return ts > MILLIS_THRESHOLD ? ts / 1000 : ts;
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
