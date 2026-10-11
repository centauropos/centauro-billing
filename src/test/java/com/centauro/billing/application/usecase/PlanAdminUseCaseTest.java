package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.PlanAdminView;
import com.centauro.billing.application.dto.PlanPriceRequest;
import com.centauro.billing.application.dto.PlanPriceView;
import com.centauro.billing.application.dto.PlanRequest;
import com.centauro.billing.application.dto.PlanUpdateRequest;
import com.centauro.billing.application.service.SubscriptionViewAssembler;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.exception.PlanCustomPricingException;
import com.centauro.billing.domain.policy.AccessPolicy;
import com.centauro.billing.domain.policy.AllowedActionsPolicy;
import com.centauro.billing.domain.policy.NoticePolicy;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.support.InMemoryPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static com.centauro.billing.support.TestFixtures.AR;
import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.TERMS;
import static com.centauro.billing.support.TestFixtures.clockAt;
import static com.centauro.billing.support.TestFixtures.customPlan;
import static com.centauro.billing.support.TestFixtures.plan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanAdminUseCaseTest {

    private static final Actor ADMIN = Actor.platformAdmin("admin@centauro", "42");

    private InMemoryPlanRepository plans;
    private PlanAdminUseCase useCase;

    @BeforeEach
    void setUp() {
        plans = new InMemoryPlanRepository();
        plans.put(plan("PROFESIONAL", true, new BigDecimal("59900")));
        useCase = new PlanAdminUseCase(plans, new SubscriptionViewAssembler(new AccessPolicy(TERMS),
                new NoticePolicy(AR), new AllowedActionsPolicy(), TERMS), clockAt(NOW));
    }

    @Test
    void priceMustStartInTheFuture() {
        assertThatThrownBy(() -> useCase.addPrice("PROFESIONAL", new PlanPriceRequest(new BigDecimal("30000"), NOW), ADMIN))
                .isInstanceOf(InvalidBillingRequestException.class);
        assertThatThrownBy(() -> useCase.addPrice("PROFESIONAL",
                new PlanPriceRequest(new BigDecimal("30000"), NOW.minusDays(1)), ADMIN))
                .isInstanceOf(InvalidBillingRequestException.class);
        assertThat(useCase.listPrices("PROFESIONAL")).hasSize(1);
    }

    @Test
    void futurePriceIsAdded() {
        PlanPriceView view = useCase.addPrice("PROFESIONAL",
                new PlanPriceRequest(new BigDecimal("30000"), NOW.plusSeconds(1)), ADMIN);

        assertThat(view.amount()).isEqualTo("30000.00");
        assertThat(view.createdBy()).isEqualTo("admin@centauro");
        assertThat(useCase.listPrices("PROFESIONAL")).hasSize(2);
    }

    @Test
    void priceWithMoreThanTwoDecimalsIsRejectedInsteadOfRounded() {
        assertThatThrownBy(() -> useCase.addPrice("PROFESIONAL",
                new PlanPriceRequest(new BigDecimal("30000.001"), NOW.plusDays(1)), ADMIN))
                .isInstanceOf(InvalidBillingRequestException.class);
    }

    @Test
    void customPricingPlanRejectsVersionedPrices() {
        plans.put(customPlan("EMPRESA", true));

        assertThatThrownBy(() -> useCase.addPrice("EMPRESA",
                new PlanPriceRequest(new BigDecimal("90000"), NOW.plusDays(1)), ADMIN))
                .isInstanceOf(PlanCustomPricingException.class)
                .hasFieldOrPropertyWithValue("code", "PLAN_CUSTOM_PRICING");
        assertThat(useCase.listPrices("EMPRESA")).isEmpty();
    }

    @Test
    void createAndUpdateMapCustomPricing() {
        PlanAdminView created = useCase.createPlan(new PlanRequest("A_MEDIDA", "A medida", null, true, true, true,
                50, Set.of("SALES_CASH"), Map.of()), ADMIN);
        assertThat(created.customPricing()).isTrue();
        assertThat(created.currentPrice()).isNull();

        PlanAdminView updated = useCase.updatePlan("A_MEDIDA", new PlanUpdateRequest("A medida", null, null, null,
                false, null, null, null), ADMIN);
        assertThat(updated.customPricing()).isFalse();
    }

    @Test
    void planWithPricesCannotBecomeCustomPricing() {
        assertThatThrownBy(() -> useCase.updatePlan("PROFESIONAL", new PlanUpdateRequest("Profesional", null, null,
                null, true, null, null, null), ADMIN))
                .isInstanceOf(PlanCustomPricingException.class);
        assertThat(useCase.listPlans()).singleElement().extracting(PlanAdminView::customPricing).isEqualTo(false);
    }

    @Test
    void actorLogIdNeverExposesTheUsername() {
        assertThat(ADMIN.logId()).isEqualTo("PLATFORM_ADMIN:42").doesNotContain("admin@centauro");
        assertThat(Actor.platformAdmin("admin@centauro").logId()).doesNotContain("admin@centauro");
    }
}
