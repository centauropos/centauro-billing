package com.centauro.billing.infrastructure.mercadopago.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Respuesta de {@code /preapproval}. Solo se leen los campos que usa la sincronización; el resto (incluidos
 * los datos del pagador) se descarta. Fechas como texto: MP las manda en ISO-8601 con milisegundos y offset.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MpPreapproval(
        String id,
        String status,
        @JsonProperty("external_reference") String externalReference,
        @JsonProperty("init_point") String initPoint,
        @JsonProperty("auto_recurring") AutoRecurring autoRecurring,
        @JsonProperty("next_payment_date") String nextPaymentDate,
        @JsonProperty("last_modified") String lastModified,
        @JsonProperty("date_created") String dateCreated
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AutoRecurring(
            @JsonProperty("transaction_amount") BigDecimal transactionAmount,
            @JsonProperty("currency_id") String currencyId
    ) {
    }
}
