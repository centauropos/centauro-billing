package com.centauro.billing.application.service;

import com.centauro.billing.application.dto.AccessView;
import com.centauro.billing.application.dto.NoticeView;
import com.centauro.billing.application.dto.PaymentView;
import com.centauro.billing.application.dto.PlanAdminView;
import com.centauro.billing.application.dto.PlanPriceView;
import com.centauro.billing.application.dto.PlanSummaryView;
import com.centauro.billing.application.dto.PriceView;
import com.centauro.billing.application.dto.SubscriptionEventView;
import com.centauro.billing.application.dto.SubscriptionListItem;
import com.centauro.billing.application.dto.TenantSubscriptionView;
import com.centauro.billing.domain.enums.AllowedAction;
import com.centauro.billing.domain.enums.SubscriptionStatus;
import com.centauro.billing.domain.model.AccessDecision;
import com.centauro.billing.domain.model.Payment;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.PlanPrice;
import com.centauro.billing.domain.model.Subscription;
import com.centauro.billing.domain.model.SubscriptionEvent;
import com.centauro.billing.domain.policy.AccessPolicy;
import com.centauro.billing.domain.policy.AllowedActionsPolicy;
import com.centauro.billing.domain.policy.BillingTerms;
import com.centauro.billing.domain.policy.NoticePolicy;
import com.centauro.billing.domain.vo.Amounts;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

/** Arma las vistas REST a partir del dominio. Todas las fechas salen con offset de la zona de negocio. */
@Component
public class SubscriptionViewAssembler {

    public static final int ACCESS_CACHE_TTL_SECONDS = 60;

    private final AccessPolicy accessPolicy;
    private final NoticePolicy noticePolicy;
    private final AllowedActionsPolicy allowedActionsPolicy;
    private final ZoneId zone;

    public SubscriptionViewAssembler(AccessPolicy accessPolicy, NoticePolicy noticePolicy,
                                     AllowedActionsPolicy allowedActionsPolicy, BillingTerms terms) {
        this.accessPolicy = accessPolicy;
        this.noticePolicy = noticePolicy;
        this.allowedActionsPolicy = allowedActionsPolicy;
        this.zone = terms.zone();
    }

    public TenantSubscriptionView tenantView(Subscription subscription, Plan plan, OffsetDateTime now) {
        AccessDecision decision = accessPolicy.evaluate(subscription, now);
        PriceView price = subscription.getPriceAmount() != null
                ? PriceView.monthly(subscription.getPriceAmount(), subscription.getCurrency())
                : plan == null ? null
                : plan.currentPrice(now).map(p -> PriceView.monthly(p.amount(), p.currency())).orElse(null);
        Integer trialDaysLeft = decision.effectiveStatus() == SubscriptionStatus.TRIALING
                ? noticePolicy.daysUntil(subscription.getTrialEndsAt(), now) : null;
        return new TenantSubscriptionView(
                subscription.getTenantId(),
                decision.effectiveStatus().name(),
                decision.accessLevel().name(),
                plan == null ? null : new PlanSummaryView(plan.getCode(), plan.getName()),
                price,
                local(subscription.getTrialEndsAt()),
                trialDaysLeft,
                local(subscription.getCurrentPeriodStart()),
                local(subscription.getCurrentPeriodEnd()),
                local(decision.effectiveGraceEndsAt()),
                subscription.isCancelAtPeriodEnd(),
                "authorized".equalsIgnoreCase(subscription.getGatewayStatus()),
                local(subscription.getNextChargeAt()),
                null,
                NoticeView.from(noticePolicy.notice(subscription, decision, now)),
                allowedActionsPolicy.allowedActions(subscription, decision).stream().map(AllowedAction::name).toList());
    }

    public AccessView accessView(String tenantId, Subscription subscription, Plan plan, OffsetDateTime now) {
        AccessDecision decision = accessPolicy.evaluate(subscription, now);
        if (!decision.known()) {
            return new AccessView(tenantId, false, null, null, decision.accessLevel().name(), null, Set.of(),
                    Map.of(), null, null, null, false, null, local(now), ACCESS_CACHE_TTL_SECONDS);
        }
        return new AccessView(
                tenantId,
                true,
                decision.status().name(),
                decision.effectiveStatus().name(),
                decision.accessLevel().name(),
                plan == null ? null : plan.getCode(),
                plan == null ? Set.of() : plan.getFeatures(),
                plan == null ? Map.of() : plan.getLimits(),
                local(subscription.getTrialEndsAt()),
                local(subscription.getCurrentPeriodEnd()),
                local(decision.effectiveGraceEndsAt()),
                subscription.isCancelAtPeriodEnd(),
                NoticeView.from(noticePolicy.notice(subscription, decision, now)),
                local(now),
                ACCESS_CACHE_TTL_SECONDS);
    }

    public SubscriptionListItem listItem(Subscription subscription, Plan plan, OffsetDateTime now) {
        AccessDecision decision = accessPolicy.evaluate(subscription, now);
        return new SubscriptionListItem(
                subscription.getTenantId(),
                subscription.getCompanyName(),
                subscription.getBillingEmail(),
                decision.effectiveStatus().name(),
                decision.accessLevel().name(),
                plan == null ? null : plan.getCode(),
                local(subscription.getTrialEndsAt()),
                local(subscription.getCurrentPeriodEnd()),
                local(decision.effectiveGraceEndsAt()),
                subscription.isCancelAtPeriodEnd(),
                subscription.getGatewayStatus(),
                local(subscription.getCreatedAt()));
    }

    public PaymentView paymentView(Payment payment) {
        return new PaymentView(payment.id(), payment.source().name(), payment.status().name(),
                Amounts.format(payment.amount()), payment.currency(), payment.gatewayPaymentId(),
                local(payment.periodStart()), local(payment.periodEnd()), local(payment.paidAt()),
                payment.applied(), payment.reference(), payment.notes(), payment.registeredBy());
    }

    public SubscriptionEventView eventView(SubscriptionEvent event) {
        return new SubscriptionEventView(event.type().name(),
                event.fromStatus() == null ? null : event.fromStatus().name(),
                event.toStatus() == null ? null : event.toStatus().name(),
                event.actorType().name(), event.actor(), event.reason(), event.data(),
                local(event.occurredAt()));
    }

    public PlanAdminView planAdminView(Plan plan, OffsetDateTime now) {
        return new PlanAdminView(plan.getId(), plan.getCode(), plan.getName(), plan.getDescription(),
                plan.isActive(), plan.isPublicVisible(), plan.getSortOrder(), plan.getFeatures(), plan.getLimits(),
                plan.currentPrice(now).map(this::priceView).orElse(null),
                plan.upcomingPrices(now).stream().map(this::priceView).toList());
    }

    public PlanPriceView priceView(PlanPrice price) {
        return new PlanPriceView(price.id(), Amounts.format(price.amount()), price.currency(),
                price.period().name(), local(price.validFrom()), price.createdBy());
    }

    public OffsetDateTime local(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(zone).toOffsetDateTime();
    }
}
