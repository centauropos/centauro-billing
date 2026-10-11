package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.ManualPaymentRequest;
import com.centauro.billing.application.dto.ManualPaymentResult;
import com.centauro.billing.application.service.PaymentApplier;
import com.centauro.billing.application.service.SubscriptionViewAssembler;
import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.exception.DuplicatePaymentException;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
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

/** Pago manual registrado desde el backoffice (T5 sin pasarela). */
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
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidBillingRequestException("El importe debe ser mayor a cero");
        }
        if (request.currency() != null && !Amounts.CURRENCY_ARS.equals(request.currency())) {
            throw new InvalidBillingRequestException("La única moneda admitida es ARS");
        }
        String reference = request.reference() == null || request.reference().isBlank()
                ? null : request.reference().trim();

        OffsetDateTime now = OffsetDateTime.now(clock);
        Subscription subscription = subscriptions.findByTenantIdForUpdate(tenantId)
                .orElseThrow(() -> new SubscriptionNotFoundException(tenantId));
        if (reference != null && payments.existsManualReference(tenantId, reference)) {
            throw new DuplicatePaymentException(reference);
        }

        PeriodCalculator.Period period = periodCalculator.nextPeriod(subscription, now, request.periodEnd());
        if (!period.end().isAfter(period.start())) {
            throw new InvalidBillingRequestException("El fin del período debe ser posterior a "
                    + assembler.local(period.start()));
        }
        Payment payment = payments.save(Payment.manual(subscription, request.amount(),
                request.paidAt() != null ? request.paidAt() : now, period.start(), period.end(), reference,
                request.notes(), actor.name(), now));

        PaymentApplier.Result result = paymentApplier.apply(subscription, payment, GatewayName.MANUAL, actor,
                request.reason(), now);
        Subscription current = result.subscription();
        return new ManualPaymentResult(assembler.paymentView(result.payment()),
                assembler.tenantView(current, plans.findById(current.getPlanId()).orElse(null), now));
    }
}
