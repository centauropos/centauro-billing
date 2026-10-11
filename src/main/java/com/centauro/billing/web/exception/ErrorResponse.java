package com.centauro.billing.web.exception;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public record ErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String correlationId
) {

    /** Única forma de armar el cuerpo de error: el timestamp sale siempre con el offset de la zona de negocio. */
    public static ErrorResponse of(Clock clock, ZoneId zone, int status, String error, String code, String message,
                                   String path, String correlationId) {
        return new ErrorResponse(OffsetDateTime.now(clock).atZoneSameInstant(zone).toOffsetDateTime(), status, error,
                code, message, path, correlationId);
    }
}