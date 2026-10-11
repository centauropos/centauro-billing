package com.centauro.billing.application.usecase;

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

/**
 * Checkout del tenant: adhesión al débito automático con un preapproval sin plan. Nunca activa nada: la
 * suscripción solo se activa o renueva con un cobro aprobado y reconsultado (webhook o conciliación).
 *
 * <p>Sin transacción propia: persiste el checkout, llama a la pasarela y guarda el resultado en pasos
 * separados ({@link CheckoutTransactions}).
 */
@Service
public class CheckoutUseCase {

    static final String GATEWAY_ERROR_MESSAGE = "No se pudo iniciar el pago con Mercado Pago";

    private static final Logger log = LoggerFactory.getLogger(CheckoutUseCase.class);

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

    public CheckoutView start(String tenantId, CheckoutRequest request, Actor actor) {
        PaymentGateway gateway = gatewayOperations.requireGateway();
        if (!settings.hasBackUrl()) {
            throw new GatewayUnavailableException("Falta configurar BILLING_CHECKOUT_BACK_URL");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        CheckoutTransactions.Prepared prepared = transactions.prepare(tenantId, request.planCode(),
                request.payerEmail(), actor, now);
        gatewayOperations.cancelQuietly(prepared.supersededPreapprovalId(), "checkout reemplazado");
        if (prepared.reused()) {
            return view(prepared.checkout(), prepared.plan(), true);
        }

        GatewayCheckout checkout = prepared.checkout();
        RecurringCheckoutResult result;
        try {
            result = gateway.createRecurringCheckout(new RecurringCheckoutRequest(checkout.getId(),
                    checkout.getPayerEmail(), "Centauro - plan " + prepared.plan().getName(),
                    checkout.getPriceAmount(), checkout.getCurrency(), checkout.getStartDate(),
                    backUrl(checkout.getId())));
        } catch (RuntimeException e) {
            markFailed(checkout.getId());
            // El detalle de MP (ya enmascarado por el adaptador) va solo al log; al tenant, un mensaje genérico.
            log.warn("Checkout {}: Mercado Pago no creó el preapproval: {}", checkout.getId(), e.getMessage());
            throw genericGatewayError(e);
        }

        GatewayCheckout attached = transactions.attach(checkout.getId(), result, OffsetDateTime.now(clock));
        if (!attached.isPending()) {
            gatewayOperations.cancelQuietly(result.gatewaySubscriptionId(), "checkout " + attached.getStatus());
            throw new CheckoutNotAllowedException("Otro checkout reemplazó a este mientras se creaba; reintentar");
        }
        log.info("Checkout {} creado para el tenant {} (preapproval {}) por {}", attached.getId(), tenantId,
                result.gatewaySubscriptionId(), actor.logId());
        return view(attached, prepared.plan(), false);
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

    private void markFailed(UUID checkoutId) {
        try {
            transactions.markFailed(checkoutId, OffsetDateTime.now(clock));
        } catch (RuntimeException e) {
            log.error("No se pudo marcar FAILED el checkout {}", checkoutId, e);
        }
    }

    private String backUrl(UUID checkoutId) {
        String base = settings.checkoutBackUrl().trim();
        return base + (base.contains("?") ? "&" : "?") + "checkout=" + checkoutId;
    }

    private CheckoutView view(GatewayCheckout checkout, Plan plan, boolean reused) {
        return new CheckoutView(checkout.getId(), checkout.getInitPoint(), assembler.local(checkout.getExpiresAt()),
                assembler.local(checkout.getStartDate()), Amounts.format(checkout.getPriceAmount()),
                checkout.getCurrency(), plan.getCode(), reused);
    }
}
