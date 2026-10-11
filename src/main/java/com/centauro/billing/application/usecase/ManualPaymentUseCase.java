package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.ManualPaymentRequest;
import com.centauro.billing.application.dto.ManualPaymentResult;
import com.centauro.billing.application.service.PaymentApplier;
import com.centauro.billing.application.service.SubscriptionViewAssembler;
import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.exception.DuplicatePaymentException;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.exception.SubscriptionAdminSuspendedException;
import com.centauro.billing.domain.exception.SubscriptionNotFoundException;
import com.centauro.billing.domain.model.Payment;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.policy.PeriodCalculator;
import com.centauro.billing.domain.port.PaymentRepositoryPort;
import com.centauro.billing.domain.port.PlanRepositoryPort;
import com.centauro.billing.domain.port.SubscriptionRepositoryPort;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.domain.vo.Amounts;
import com.centauro.billing.domain.vo.TenantIds;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Pago manual registrado desde el backoffice (T5 sin pasarela). Idempotente por {@code (tenant, reference)}:
 * reintentar con la misma referencia devuelve el pago original sin aplicar otro período.
 */
@Service
public class ManualPaymentUseCase {

    private final SubscriptionRepositoryPort subscriptions;
    private final PaymentRepositoryPort payments;
    private final PlanRepositoryPort plans;
    private final PaymentApplier paymentApplier;
    private final PeriodCalculator periodCalculator;
    private final SubscriptionViewAssembler assembler;
    private final Clock clock;

    public ManualPaymentUseCase(SubscriptionRepositoryPort subscriptions, PaymentRepositoryPort payments,
                                PlanRepositoryPort plans, PaymentApplier paymentApplier,
                                PeriodCalculator periodCalculator, SubscriptionViewAssembler assembler,
                                Clock clock) {
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.plans = plans;
        this.paymentApplier = paymentApplier;
        this.periodCalculator = periodCalculator;
        this.assembler = assembler;
        this.clock = clock;
    }

    @Transactional
    public ManualPaymentResult register(String tenantId, ManualPaymentRequest request, Actor actor) {
        TenantIds.requireValid(tenantId);
        BigDecimal amount = Amounts.requirePositiveMoney(request.amount());
        if (request.currency() != null && !Amounts.CURRENCY_ARS.equals(request.currency())) {
            throw new InvalidBillingRequestException("La única moneda admitida es ARS");
        }
        if (request.reference() == null || request.reference().isBlank()) {
            throw new InvalidBillingRequestException("La referencia del pago es obligatoria");
        }
        String reference = request.reference().trim();

        OffsetDateTime now = OffsetDateTime.now(clock);
        // El bloqueo de la suscripción serializa los reintentos concurrentes con la misma referencia.
        Subscription subscription = subscriptions.findByTenantIdForUpdate(tenantId)
                .orElseThrow(() -> new SubscriptionNotFoundException(tenantId));
        Optional<Payment> existing = payments.findManualByReference(tenantId, reference);
        if (existing.isPresent()) {
            if (existing.get().amount().compareTo(amount) != 0) {
                throw new DuplicatePaymentException(reference);
            }
            return result(existing.get(), subscription, now, false);
        }
        if (subscription.isAdminSuspended()) {
            throw new SubscriptionAdminSuspendedException(tenantId);
        }

        PeriodCalculator.Period period = periodCalculator.nextPeriod(subscription, now, request.periodEnd());
        if (!period.end().isAfter(period.start())) {
            throw new InvalidBillingRequestException("El fin del período debe ser posterior a "
                    + assembler.local(period.start()));
        }
        Payment payment = payments.save(Payment.manual(subscription, amount,
                request.paidAt() != null ? request.paidAt() : now, period.start(), period.end(), reference,
                request.notes(), actor.name(), now));

        PaymentApplier.Result applied = paymentApplier.apply(subscription, payment, period.anchorDay(),
                GatewayName.MANUAL, actor, request.reason(), now);
        return result(applied.payment(), applied.subscription(), now, true);
    }

    private ManualPaymentResult result(Payment payment, Subscription subscription, OffsetDateTime now,
                                       boolean created) {
        return new ManualPaymentResult(assembler.paymentView(payment),
                assembler.tenantView(subscription, plans.findById(subscription.getPlanId()).orElse(null), now),
                created);
    }
}
