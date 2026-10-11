package com.centauro.billing.infrastructure.mercadopago;

import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.exception.GatewayRejectedException;
import com.centauro.billing.domain.exception.GatewayTimeoutException;
import com.centauro.billing.domain.exception.GatewayUnavailableException;
import com.centauro.billing.domain.port.PaymentGateway;
import com.centauro.billing.domain.vo.Amounts;
import com.centauro.billing.domain.vo.GatewayChargeSnapshot;
import com.centauro.billing.domain.vo.GatewaySubscriptionSnapshot;
import com.centauro.billing.domain.vo.RecurringCheckoutRequest;
import com.centauro.billing.domain.vo.RecurringCheckoutResult;
import com.centauro.billing.infrastructure.mercadopago.dto.MpAuthorizedPayment;
import com.centauro.billing.infrastructure.mercadopago.dto.MpPreapproval;
import com.centauro.billing.infrastructure.mercadopago.dto.MpPreapprovalRequest;
import com.centauro.billing.infrastructure.mercadopago.dto.MpPreapprovalUpdate;
import com.centauro.billing.infrastructure.mercadopago.dto.MpSearchResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Adaptador de Mercado Pago sobre {@link RestClient}, sin SDK. Suscripciones con preapproval sin plan, en ARS.
 * El {@link RestClient} ya trae base URL, timeouts y el header {@code Authorization} (ver
 * {@link MercadoPagoConfiguration}); el token nunca pasa por esta clase ni por los logs.
 *
 * <p>Errores: 4xx → {@link GatewayRejectedException}; 5xx o red → {@link GatewayUnavailableException};
 * timeout → {@link GatewayTimeoutException}. Los GET se reintentan una vez ante 5xx o error de red; POST y PUT
 * nunca (la creación es idempotente por {@code X-Idempotency-Key}, la cancelación no).
 */
public class MercadoPagoGateway implements PaymentGateway {

    static final int SEARCH_PAGE_SIZE = 50;
    static final int MAX_SEARCH_PAGES = 20;
    static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";
    /** Verificar contra MP: se asume {@code cancelled} para escribir (al leer se aceptan ambas grafías). */
    static final String CANCELLED_STATUS = "cancelled";

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoGateway.class);
    private static final DateTimeFormatter MP_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@\"]+@[^\\s@\"]+");
    private static final int MAX_DETAIL = 200;

    private final RestClient client;
    private final String notificationUrl;
    private final ObjectMapper objectMapper;

    public MercadoPagoGateway(RestClient client, String notificationUrl, ObjectMapper objectMapper) {
        this.client = client;
        this.notificationUrl = notificationUrl;
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayName name() {
        return GatewayName.MERCADOPAGO;
    }

    @Override
    public RecurringCheckoutResult createRecurringCheckout(RecurringCheckoutRequest request) {
        String checkoutId = request.checkoutId().toString();
        MpPreapprovalRequest body = new MpPreapprovalRequest(request.reason(), checkoutId, request.payerEmail(),
                request.backUrl(), notificationUrl,
                new MpPreapprovalRequest.AutoRecurring(1, "months", format(request.startDate()),
                        Amounts.scale(request.amount()), request.currency()),
                "pending");
        MpPreapproval created = call("la creación del preapproval", false, () -> client.post()
                .uri("/preapproval")
                .header(IDEMPOTENCY_HEADER, checkoutId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(MpPreapproval.class));
        if (created == null || isBlank(created.id()) || isBlank(created.initPoint())) {
            throw new GatewayUnavailableException("Mercado Pago devolvió un preapproval sin id o sin init_point");
        }
        log.info("Mercado Pago: preapproval {} creado para el checkout {}", created.id(), checkoutId);
        return new RecurringCheckoutResult(created.id(), created.initPoint(), created.status());
    }

    @Override
    public GatewaySubscriptionSnapshot getSubscription(String gatewaySubscriptionId) {
        MpPreapproval preapproval = call("la consulta del preapproval", true, () -> client.get()
                .uri("/preapproval/{id}", gatewaySubscriptionId)
                .retrieve()
                .body(MpPreapproval.class));
        if (preapproval == null || isBlank(preapproval.id())) {
            throw new GatewayUnavailableException("Mercado Pago devolvió un preapproval vacío");
        }
        return MercadoPagoStatusMapper.preapproval(preapproval);
    }

    @Override
    public GatewayChargeSnapshot getCharge(String gatewayChargeId) {
        MpAuthorizedPayment charge = call("la consulta del cobro", true, () -> client.get()
                .uri("/authorized_payments/{id}", gatewayChargeId)
                .retrieve()
                .body(MpAuthorizedPayment.class));
        if (charge == null || isBlank(charge.id())) {
            throw new GatewayUnavailableException("Mercado Pago devolvió un cobro vacío");
        }
        return MercadoPagoStatusMapper.authorizedPayment(charge);
    }

    /** Verificar contra MP que exista {@code /authorized_payments/search} y cómo pagina. */
    @Override
    public List<GatewayChargeSnapshot> listCharges(String gatewaySubscriptionId) {
        List<GatewayChargeSnapshot> charges = new ArrayList<>();
        for (int page = 0; page < MAX_SEARCH_PAGES; page++) {
            int offset = page * SEARCH_PAGE_SIZE;
            MpSearchResponse<MpAuthorizedPayment> response = call("la búsqueda de cobros", true, () -> client.get()
                    .uri(builder -> builder.path("/authorized_payments/search")
                            .queryParam("preapproval_id", gatewaySubscriptionId)
                            .queryParam("offset", offset)
                            .queryParam("limit", SEARCH_PAGE_SIZE)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<MpSearchResponse<MpAuthorizedPayment>>() {
                    }));
            List<MpAuthorizedPayment> results = response == null || response.results() == null
                    ? List.of() : response.results();
            results.stream().filter(r -> !isBlank(r.id()))
                    .map(MercadoPagoStatusMapper::authorizedPayment)
                    .forEach(charges::add);
            Integer total = response == null || response.paging() == null ? null : response.paging().total();
            if (results.size() < SEARCH_PAGE_SIZE || (total != null && offset + results.size() >= total)) {
                break;
            }
        }
        return charges;
    }

    /** Verificar contra MP si un aumento exige un nuevo consentimiento del pagador. */
    @Override
    public void updateAmount(String gatewaySubscriptionId, BigDecimal amount) {
        MpPreapprovalUpdate body = new MpPreapprovalUpdate(null,
                new MpPreapprovalRequest.AutoRecurring(null, null, null, Amounts.scale(amount),
                        Amounts.CURRENCY_ARS));
        put(gatewaySubscriptionId, body, "el cambio de importe");
        log.info("Mercado Pago: importe del preapproval {} actualizado", gatewaySubscriptionId);
    }

    @Override
    public void cancel(String gatewaySubscriptionId) {
        put(gatewaySubscriptionId, new MpPreapprovalUpdate(CANCELLED_STATUS, null), "la cancelación");
        log.info("Mercado Pago: preapproval {} cancelado", gatewaySubscriptionId);
    }

    private void put(String gatewaySubscriptionId, MpPreapprovalUpdate body, String operation) {
        call(operation + " del preapproval", false, () -> client.put()
                .uri("/preapproval/{id}", gatewaySubscriptionId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity());
    }

    private <T> T call(String operation, boolean retryable, Supplier<T> request) {
        int maxAttempts = retryable ? 2 : 1;
        for (int attempt = 1; ; attempt++) {
            boolean last = attempt >= maxAttempts;
            try {
                return request.get();
            } catch (HttpClientErrorException e) {
                throw new GatewayRejectedException("Mercado Pago rechazó " + operation + " ("
                        + e.getStatusCode().value() + ")" + detail(e));
            } catch (HttpServerErrorException e) {
                if (last) {
                    throw new GatewayUnavailableException("Mercado Pago falló en " + operation + " ("
                            + e.getStatusCode().value() + ")");
                }
                log.warn("Mercado Pago: {} devolvió {}; se reintenta", operation, e.getStatusCode().value());
            } catch (ResourceAccessException e) {
                boolean timeout = isTimeout(e);
                if (last) {
                    if (timeout) {
                        throw new GatewayTimeoutException("Mercado Pago no respondió a tiempo en " + operation);
                    }
                    throw new GatewayUnavailableException("No se pudo conectar con Mercado Pago en " + operation);
                }
                log.warn("Mercado Pago: error de red en {}; se reintenta", operation);
            } catch (RestClientException e) {
                throw new GatewayUnavailableException("Respuesta inválida de Mercado Pago en " + operation);
            }
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** El {@code message} del error de MP, acotado y sin emails (puede citar el {@code payer_email}). */
    private String detail(HttpStatusCodeException e) {
        try {
            JsonNode node = objectMapper.readTree(e.getResponseBodyAsString());
            String message = node == null ? null : node.path("message").asText(null);
            if (isBlank(message)) {
                return "";
            }
            String clean = EMAIL.matcher(message).replaceAll("***");
            return ": " + (clean.length() > MAX_DETAIL ? clean.substring(0, MAX_DETAIL) : clean);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String format(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).format(MP_DATE);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
