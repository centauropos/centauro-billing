package com.centauro.billing.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Prepare del checkout. Solo {@code planCode} importa: el email hacia MP sale siempre del
 * {@code billingEmail} de la suscripción. {@code payerEmail} está deprecado y se ignora.
 */
public record CheckoutRequest(
        @NotBlank @Size(max = 40) String planCode,
        @Email @Size(max = 255) String payerEmail
) {
}
