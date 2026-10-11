package com.centauro.billing.web.exception;

import com.centauro.billing.domain.exception.BillingException;
import com.centauro.billing.domain.exception.CheckoutNotAllowedException;
import com.centauro.billing.domain.exception.DuplicatePaymentException;
import com.centauro.billing.domain.exception.DuplicatePlanException;
import com.centauro.billing.domain.exception.DuplicatePlanPriceException;
import com.centauro.billing.domain.exception.GatewayRejectedException;
import com.centauro.billing.domain.exception.GatewayTimeoutException;
import com.centauro.billing.domain.exception.GatewayUnavailableException;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.exception.InvalidSubscriptionTransitionException;
import com.centauro.billing.domain.exception.PlanNotFoundException;
import com.centauro.billing.domain.exception.PlanPriceNotFoundException;
import com.centauro.billing.domain.exception.PlanWithoutPriceException;
import com.centauro.billing.domain.exception.PriceAlreadyEffectiveException;
import com.centauro.billing.domain.exception.SamePlanException;
import com.centauro.billing.domain.exception.SubscriptionNotFoundException;
import com.centauro.billing.web.filter.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Map<Class<? extends BillingException>, HttpStatus> STATUS_BY_EXCEPTION = Map.ofEntries(
            Map.entry(SubscriptionNotFoundException.class, HttpStatus.NOT_FOUND),
            Map.entry(PlanNotFoundException.class, HttpStatus.NOT_FOUND),
            Map.entry(PlanPriceNotFoundException.class, HttpStatus.NOT_FOUND),
            Map.entry(InvalidSubscriptionTransitionException.class, HttpStatus.CONFLICT),
            Map.entry(CheckoutNotAllowedException.class, HttpStatus.CONFLICT),
            Map.entry(DuplicatePlanException.class, HttpStatus.CONFLICT),
            Map.entry(DuplicatePlanPriceException.class, HttpStatus.CONFLICT),
            Map.entry(DuplicatePaymentException.class, HttpStatus.CONFLICT),
            Map.entry(SamePlanException.class, HttpStatus.CONFLICT),
            Map.entry(PriceAlreadyEffectiveException.class, HttpStatus.CONFLICT),
            Map.entry(PlanWithoutPriceException.class, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(GatewayRejectedException.class, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(InvalidBillingRequestException.class, HttpStatus.BAD_REQUEST),
            Map.entry(GatewayUnavailableException.class, HttpStatus.BAD_GATEWAY),
            Map.entry(GatewayTimeoutException.class, HttpStatus.GATEWAY_TIMEOUT));

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(BillingException.class)
    public ResponseEntity<ErrorResponse> handleBilling(BillingException ex, HttpServletRequest request) {
        HttpStatus status = STATUS_BY_EXCEPTION.getOrDefault(ex.getClass(), HttpStatus.INTERNAL_SERVER_ERROR);
        if (status.is5xxServerError()) {
            log.warn("Error de billing {}: {}", ex.getCode(), ex.getMessage());
        }
        return build(status, ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, ConstraintViolationException.class,
            HandlerMethodValidationException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Solicitud inválida", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "Sin permisos para esta operación", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex,
                                                              HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "No autenticado", request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                              HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "La suscripción fue modificada por otra operación; reintentar", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException ex,
                                                         HttpServletRequest request) {
        log.warn("Violación de integridad: {}", ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "CONFLICT", "La operación entra en conflicto con datos existentes",
                request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Recurso inexistente", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex,
                                                      HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Método no soportado", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Error no controlado en {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Error interno", request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
                                                HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(OffsetDateTime.now(clock), status.value(),
                status.getReasonPhrase(), code, message, request.getRequestURI(),
                MDC.get(CorrelationIdFilter.MDC_KEY)));
    }
}
