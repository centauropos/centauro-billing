package com.centauro.billing.infrastructure.mercadopago;

import com.centauro.billing.domain.enums.WebhookSignatureResult;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class MercadoPagoSignatureVerifierTest {

    private static final String SECRET = "webhook-secret";
    private static final long TS = 1_760_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(TS + 60), ZoneOffset.UTC);

    private static MercadoPagoVerifierBuilder verifier() {
        return new MercadoPagoVerifierBuilder();
    }

    private static String sign(String manifest, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String header(long ts, String dataId, String requestId, String secret) {
        String manifest = "id:" + dataId + ";request-id:" + requestId + ";ts:" + ts + ";";
        return "ts=" + ts + ",v1=" + sign(manifest, secret);
    }

    @Test
    void validSignatureIsAccepted() {
        assertThat(verifier().build().verify(header(TS, "123456", "req-1", SECRET), "req-1", "123456"))
                .isEqualTo(WebhookSignatureResult.VALID);
    }

    @Test
    void headerPartsMayComeInAnyOrderWithSpaces() {
        String v1 = sign("id:123456;request-id:req-1;ts:" + TS + ";", SECRET);

        assertThat(verifier().build().verify(" v1=" + v1.toUpperCase() + " , ts=" + TS, "req-1", "123456"))
                .isEqualTo(WebhookSignatureResult.VALID);
    }

    @Test
    void alphanumericDataIdIsSignedInLowercase() {
        String signature = header(TS, "abc123", "req-1", SECRET);

        assertThat(verifier().build().verify(signature, "req-1", "ABC123")).isEqualTo(WebhookSignatureResult.VALID);
    }

    @Test
    void absentSegmentsAreOmittedFromTheManifest() {
        assertThat(MercadoPagoSignatureVerifier.manifest(null, "req-1", "1")).isEqualTo("request-id:req-1;ts:1;");
        assertThat(MercadoPagoSignatureVerifier.manifest("9", null, "1")).isEqualTo("id:9;ts:1;");
    }

    @Test
    void wrongSecretTamperedIdOrRequestIdIsInvalid() {
        MercadoPagoSignatureVerifier verifier = verifier().build();

        assertThat(verifier.verify(header(TS, "123456", "req-1", "otro"), "req-1", "123456"))
                .isEqualTo(WebhookSignatureResult.INVALID);
        assertThat(verifier.verify(header(TS, "123456", "req-1", SECRET), "req-1", "999999"))
                .isEqualTo(WebhookSignatureResult.INVALID);
        assertThat(verifier.verify(header(TS, "123456", "req-1", SECRET), "req-2", "123456"))
                .isEqualTo(WebhookSignatureResult.INVALID);
    }

    @Test
    void malformedHeadersAreRejected() {
        MercadoPagoSignatureVerifier verifier = verifier().build();

        assertThat(verifier.verify(null, "req-1", "1")).isEqualTo(WebhookSignatureResult.MALFORMED);
        assertThat(verifier.verify("", "req-1", "1")).isEqualTo(WebhookSignatureResult.MALFORMED);
        assertThat(verifier.verify("v1=abc", "req-1", "1")).isEqualTo(WebhookSignatureResult.MALFORMED);
        assertThat(verifier.verify("ts=123", "req-1", "1")).isEqualTo(WebhookSignatureResult.MALFORMED);
        assertThat(verifier.verify("ts=abc,v1=def", "req-1", "1")).isEqualTo(WebhookSignatureResult.MALFORMED);
        assertThat(verifier.verify("basura", "req-1", "1")).isEqualTo(WebhookSignatureResult.MALFORMED);
    }

    @Test
    void timestampOutsideTheToleranceIsExpiredWhenEnabled() {
        String old = header(TS - 3600, "123456", "req-1", SECRET);
        String fresh = header(TS, "123456", "req-1", SECRET);
        String millis = header(TS * 1000, "123456", "req-1", SECRET);

        assertThat(verifier().maxSkew(300).build().verify(old, "req-1", "123456"))
                .isEqualTo(WebhookSignatureResult.EXPIRED);
        assertThat(verifier().maxSkew(300).build().verify(fresh, "req-1", "123456"))
                .isEqualTo(WebhookSignatureResult.VALID);
        assertThat(verifier().maxSkew(300).build().verify(millis, "req-1", "123456"))
                .isEqualTo(WebhookSignatureResult.VALID);
        assertThat(verifier().build().verify(old, "req-1", "123456")).isEqualTo(WebhookSignatureResult.VALID);
    }

    @Test
    void withoutSecretEverythingIsRejected() {
        assertThat(verifier().secret(null).build().verify(header(TS, "1", "r", SECRET), "r", "1"))
                .isEqualTo(WebhookSignatureResult.MISSING_SECRET);
        assertThat(verifier().secret(" ").build().verify(null, "r", "1"))
                .isEqualTo(WebhookSignatureResult.MISSING_SECRET);
    }

    @Test
    void unsignedRequestsPassOnlyWhenSignatureIsExplicitlyNotRequired() {
        assertThat(verifier().requireSignature(false).build().verify(null, "r", "1"))
                .isEqualTo(WebhookSignatureResult.VALID);
        assertThat(verifier().requireSignature(false).build().verify("ts=1,v1=00", "r", "1"))
                .isEqualTo(WebhookSignatureResult.INVALID);
    }

    private static final class MercadoPagoVerifierBuilder {
        private String secret = SECRET;
        private boolean requireSignature = true;
        private long maxSkew;

        MercadoPagoVerifierBuilder secret(String value) { secret = value; return this; }
        MercadoPagoVerifierBuilder requireSignature(boolean value) { requireSignature = value; return this; }
        MercadoPagoVerifierBuilder maxSkew(long value) { maxSkew = value; return this; }

        MercadoPagoSignatureVerifier build() {
            return new MercadoPagoSignatureVerifier(new MercadoPagoProperties("https://api.mp.test", "token", secret,
                    new MercadoPagoProperties.Webhook(requireSignature, maxSkew), 1000, 1000), CLOCK);
        }
    }
}
