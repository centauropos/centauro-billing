package com.centauro.billing.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Token de tarjeta del Brick / Card Form de Mercado Pago. Nunca se loguea. */
public record AuthorizeCheckoutRequest(
        @NotBlank @Size(max = 255) String cardTokenId
) {
}
