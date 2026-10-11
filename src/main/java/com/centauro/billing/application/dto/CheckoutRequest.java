package com.centauro.billing.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code payerEmail} opcional: si falta, se usa el email de facturación de la suscripción. */
public record CheckoutRequest(
        @NotBlank @Size(max = 40) String planCode,
        @Email @Size(max = 255) String payerEmail
) {
}
