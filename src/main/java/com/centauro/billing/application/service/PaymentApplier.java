package com.centauro.billing.application.service;

import com.centauro.billing.domain.enums.GatewayName;
import com.centauro.billing.domain.enums.PaymentStatus;
import com.centauro.billing.domain.model.Payment;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.port.PaymentRepositoryPort;
import com.centauro.billing.domain.port.SubscriptionEventRepositoryPort;
import com.centauro.billing.domain.port.SubscriptionRepositoryPort;
import com.centauro.billing.domain.vo.Actor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Aplica un pago aprobado ya persistido (T5). El flag {@code applied} se marca con un UPDATE condicional,
 * así un mismo pago nunca extiende el período dos veces.
 */
@Component
public class PaymentApplier {

    private final SubscriptionRepositoryPort subscriptions;
    private final PaymentRepositoryPort payments;
    private final SubscriptionEventRepositoryPort events;

    public PaymentApplier(SubscriptionRepositoryPort subscriptions, PaymentRepositoryPort payments,
                          SubscriptionEventRepositoryPort events) {
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.events = events;
    }

    public record Result(Payment payment, Subscription subscription, boolean applied) {
    }

    /** La suscripción tiene que venir bloqueada por el llamador. */
    public Result apply(Subscription subscription, Payment payment, GatewayName gateway, Actor actor,
                        String reason, OffsetDateTime now) {
        if (payment.status() != PaymentStatus.APPROVED || payment.applied()) {
            return new Result(payment, subscription, false);
        }
        if (subscription.isAdminSuspended()) {
            events.append(subscription.recordPaymentWithoutReactivation(payment.id(), actor, reason, now));
            return new Result(payment, subscription, false);
        }
        if (!payments.markApplied(payment.id())) {
            return new Result(payment, subscription, false);
        }
        var event = subscription.applyPayment(payment.id(), payment.periodStart(), payment.periodEnd(), gateway,
                actor, reason, now);
        Subscription saved = subscriptions.save(subscription);
        events.append(event);
        return new Result(payment.markApplied(), saved, true);
    }
}
