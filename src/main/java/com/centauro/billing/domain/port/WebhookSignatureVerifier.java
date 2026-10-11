package com.centauro.billing.domain.port;

import com.centauro.billing.domain.enums.WebhookSignatureResult;

public interface WebhookSignatureVerifier {

    /**
     * @param signatureHeader valor del header de firma ({@code x-signature} en Mercado Pago)
     * @param requestId       valor de {@code x-request-id}
     * @param dataId          id del recurso notificado ({@code data.id} del query string)
     */
    WebhookSignatureResult verify(String signatureHeader, String requestId, String dataId);
}
