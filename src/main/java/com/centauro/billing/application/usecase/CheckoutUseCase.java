package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.AuthorizeCheckoutRequest;
import com.centauro.billing.application.dto.CheckoutRequest;
import com.centauro.billing.application.dto.CheckoutView;
import com.centauro.billing.application.service.CheckoutTransactions;
import com.centauro.billing.application.service.GatewayOperations;
import com.centauro.billing.application.service.GatewaySettings;
import com.centauro.billing.application.service.SubscriptionViewAssembler;
import com.centauro.billing.domain.exception.BillingException;
import com.centauro.billing.domain.exception.CheckoutNotAllowedException;
import com.centauro.billing.domain.exception.GatewayRejectedException;
import com.centauro.billing.domain.exception.GatewayTimeoutException;
import com.centauro.billing.domain.exception.GatewayUnavailableException;
import com.centauro.billing.domain.model.GatewayCheckout;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.port.PaymentGateway;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.domain.vo.Amounts;
import com.centauro.billing.domain.vo.RecurringCheckoutRequest;
import com.centauro.billing.domain.vo.RecurringCheckoutResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Checkout del tenant en dos pasos: {@link #prepare} (PENDING local, sin MP) y {@link #authorize}
 * (preapproval without-plan-authorized con {@code card_token_id}). Nunca activa el período: la
 * suscripción solo se activa o renueva con un cobro aprobado y reconsultado (webhook o conciliación).
 */
@Service
public class CheckoutUseCase {

    static final String GATEWAY_ERROR_MESSAGE = "No se pudo iniciar el pago con Mercado Pago";

    private static final Logger log = LoggerFactory.getLogger(CheckoutUseCase.class);
    private static final Pattern EMAIL = Pattern.compile("[^\\s@\"]+@[^\\s@\"]+");
    private static final Pattern CARD_TOKEN = Pattern.compile(
            "(?i)(card_token(?:_id)?)([\"'\\s:=]+)([^\\s\"',};]+)");

    private final CheckoutTransactions transactions;
    private final GatewayOperations gatewayOperations;
    private final GatewaySettings settings;
    private final SubscriptionViewAssembler assembler;
    private final Clock clock;

    public CheckoutUseCase(CheckoutTransactions transactions, GatewayOperations gatewayOperations,
                           GatewaySettings settings, SubscriptionViewAssembler assembler, Clock clock) {
        this.transactions = transactions;
        this.gatewayOperations = gatewayOperations;
        this.settings = settings;
        this.assembler = assembler;
        this.clock = clock;
    }

    /** Prepara un checkout PENDING. No llama a la pasarela. {@code payerEmail} del request se ignora. */
    public CheckoutView prepare(String tenantId, CheckoutRequest request, Actor actor) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        CheckoutTransactions.Prepared prepared = transactions.prepare(tenantId, request.planCode(), actor, now);
        gatewayOperations.cancelQuietly(prepared.supersededPreapprovalId(), "checkout reemplazado");
        return view(prepared.checkout(), prepared.plan(), prepared.reused());
    }

    /**
     * Autoriza el débito con la tarjeta tokenizada (Brick). Idempotente si el checkout ya tiene
     * preapproval o está AUTHORIZED (200). Rechazo 4xx → FAILED; timeout/5xx → PENDING reintentable.
     */
    public CheckoutView authorize(String tenantId, UUID checkoutId, AuthorizeCheckoutRequest request, Actor actor) {
        PaymentGateway gateway = gatewayOperations.requireGateway();
        if (!settings.hasBackUrl()) {
            throw new GatewayUnavailableException("Falta configurar BILLING_CHECKOUT_BACK_URL");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        CheckoutTransactions.AuthorizeClaim claim = transactions.claimForAuthorize(tenantId, checkoutId, now);
        if (claim.alreadyDone()) {
            return view(claim.checkout(), claim.plan(), true);
        }

        GatewayCheckout checkout = claim.checkout();
        RecurringCheckoutResult result;
        try {
            result = gateway.createRecurringCheckout(new RecurringCheckoutRequest(checkout.getId(),
                    checkout.getPayerEmail(), "Centauro - plan " + claim.plan().getName(),
                    checkout.getPriceAmount(), checkout.getCurrency(), checkout.getStartDate(),
                    backUrl(checkout.getId()), request.cardTokenId()));
        } catch (RuntimeException e) {
            // Nunca loguear cardTokenId ni el message crudo sin enmascarar.
            log.warn("Checkout {}: Mercado Pago no autorizó el preapproval: {}", checkout.getId(),
                    redactForLog(e.getMessage()));
            if (e instanceof GatewayRejectedException) {
                markFailed(checkout.getId());
            } else {
                // Timeout / 5xx / error inesperado: PENDING y claim liberado para reintentar (misma idempotency key).
                clearClaim(checkout.getId());
            }
            throw genericGatewayError(e);
        }

        GatewayCheckout attached = transactions.attach(checkout.getId(), result, OffsetDateTime.now(clock));
        if (!attached.isPending() || !result.gatewaySubscriptionId().equals(attached.getGatewayPreapprovalId())) {
            gatewayOperations.cancelQuietly(result.gatewaySubscriptionId(), "checkout " + attached.getStatus());
            throw new CheckoutNotAllowedException("Otro checkout reemplazó a este mientras se autorizaba; reintentar");
        }
        log.info("Checkout {} autorizado para el tenant {} (preapproval {}) por {}", attached.getId(), tenantId,
                result.gatewaySubscriptionId(), actor.logId());
        return view(attached, claim.plan(), false);
    }

    /** Conserva el tipo (422, 502 o 504) y reemplaza el mensaje. */
    static BillingException genericGatewayError(RuntimeException e) {
        if (e instanceof GatewayRejectedException) {
            return new GatewayRejectedException(GATEWAY_ERROR_MESSAGE);
        }
        if (e instanceof GatewayTimeoutException) {
            return new GatewayTimeoutException(GATEWAY_ERROR_MESSAGE);
        }
        return new GatewayUnavailableException(GATEWAY_ERROR_MESSAGE);
    }

    /** Enmascara emails y card_token(_id) antes de loguear mensajes de la pasarela. */
    static String redactForLog(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        String withoutTokens = CARD_TOKEN.matcher(message).replaceAll("$1$2***");
        return EMAIL.matcher(withoutTokens).replaceAll("***");
    }

    private void markFailed(UUID checkoutId) {
        try {
            transactions.markFailed(checkoutId, OffsetDateTime.now(clock));
        } catch (RuntimeException e) {
            log.error("No se pudo marcar FAILED el checkout {}", checkoutId, e);
        }
    }

    private void clearClaim(UUID checkoutId) {
        try {
            transactions.clearAuthorizeClaim(checkoutId);
        } catch (RuntimeException e) {
            log.error("No se pudo liberar el claim del checkout {}", checkoutId, e);
        }
    }

    private String backUrl(UUID checkoutId) {
        String base = settings.checkoutBackUrl().trim();
        return base + (base.contains("?") ? "&" : "?") + "checkout=" + checkoutId;
    }

    private CheckoutView view(GatewayCheckout checkout, Plan plan, boolean reused) {
        return new CheckoutView(checkout.getId(), checkout.getInitPoint(), assembler.local(checkout.getExpiresAt()),
                assembler.local(checkout.getStartDate()), Amounts.format(checkout.getPriceAmount()),
                checkout.getCurrency(), plan.getCode(), checkout.getPayerEmail(), reused);
    }
}
