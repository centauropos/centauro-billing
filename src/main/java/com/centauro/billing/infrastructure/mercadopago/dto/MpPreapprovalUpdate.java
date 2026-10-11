package com.centauro.billing.infrastructure.mercadopago.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code PUT /preapproval/{id}}: o cambia el estado ({@code cancelled}) o el importe, nunca ambos. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MpPreapprovalUpdate(
        String status,
        @JsonProperty("auto_recurring") MpPreapprovalRequest.AutoRecurring autoRecurring
) {
}
