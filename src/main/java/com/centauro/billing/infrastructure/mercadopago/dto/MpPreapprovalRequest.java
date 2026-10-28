package com.centauro.billing.infrastructure.mercadopago.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * {@code POST /preapproval} sin plan ({@code preapproval_plan_id}), contrato without-plan-authorized:
 * {@code status=authorized} + {@code card_token_id}. Verificar contra la documentación vigente de MP.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MpPreapprovalRequest(
        String reason,
        @JsonProperty("external_reference") String externalReference,
        @JsonProperty("payer_email") String payerEmail,
        @JsonProperty("card_token_id") String cardTokenId,
        @JsonProperty("back_url") String backUrl,
        @JsonProperty("notification_url") String notificationUrl,
        @JsonProperty("auto_recurring") AutoRecurring autoRecurring,
        String status
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AutoRecurring(
            Integer frequency,
            @JsonProperty("frequency_type") String frequencyType,
            @JsonProperty("start_date") String startDate,
            @JsonProperty("transaction_amount") BigDecimal transactionAmount,
            @JsonProperty("currency_id") String currencyId
    ) {
    }
}
