package com.centauro.billing.infrastructure.mercadopago.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Respuesta de {@code /authorized_payments}: un cobro mensual del preapproval. {@code status} es el del cobro
 * ({@code scheduled}, {@code processed}, {@code recycling}, {@code cancelled}...) y {@code payment.status} el
 * del pago del intento ({@code approved}, {@code rejected}...). Nombres a verificar contra la doc de MP.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MpAuthorizedPayment(
        String id,
        @JsonProperty("preapproval_id") String preapprovalId,
        String status,
        @JsonProperty("transaction_amount") BigDecimal transactionAmount,
        @JsonProperty("currency_id") String currencyId,
        @JsonProperty("date_created") String dateCreated,
        @JsonProperty("debit_date") String debitDate,
        @JsonProperty("last_modified") String lastModified,
        Payment payment
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payment(
            String id,
            String status,
            @JsonProperty("status_detail") String statusDetail
    ) {
    }
}
