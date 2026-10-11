package com.centauro.billing.web.security;

import com.centauro.billing.web.exception.ErrorResponse;
import com.centauro.billing.web.filter.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** Escribe los 401/403 de las cadenas de seguridad con el mismo formato que {@code GlobalExceptionHandler}. */
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ZoneId zone;

    public SecurityErrorWriter(ObjectMapper objectMapper, Clock clock, ZoneId zone) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.zone = zone;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
                      String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        ErrorResponse body = new ErrorResponse(OffsetDateTime.now(clock).atZoneSameInstant(zone).toOffsetDateTime(),
                status.value(), status.getReasonPhrase(), code, message, request.getRequestURI(),
                MDC.get(CorrelationIdFilter.MDC_KEY));
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    public void unauthorized(HttpServletRequest request, HttpServletResponse response, String code)
            throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, code, "No autenticado");
    }

    public void forbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Sin permisos para esta operación");
    }
}