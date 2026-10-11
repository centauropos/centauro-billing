package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.PlanPriceRequest;
import com.centauro.billing.application.dto.PlanPriceView;
import com.centauro.billing.application.service.SubscriptionViewAssembler;
import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.policy.AccessPolicy;
import com.centauro.billing.domain.policy.AllowedActionsPolicy;
import com.centauro.billing.domain.policy.NoticePolicy;
import com.centauro.billing.domain.vo.Actor;
import com.centauro.billing.support.InMemoryPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.centauro.billing.support.TestFixtures.AR;
import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.TERMS;
import static com.centauro.billing.support.TestFixtures.clockAt;
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
        plans.put(plan("PRO", true, new BigDecimal("25000")));
        useCase = new PlanAdminUseCase(plans, new SubscriptionViewAssembler(new AccessPolicy(TERMS),
                new NoticePolicy(AR), new AllowedActionsPolicy(), TERMS), clockAt(NOW));
    }

    @Test
    void priceMustStartInTheFuture() {
        assertThatThrownBy(() -> useCase.addPrice("PRO", new PlanPriceRequest(new BigDecimal("30000"), NOW), ADMIN))
                .isInstanceOf(InvalidBillingRequestException.class);
        assertThatThrownBy(() -> useCase.addPrice("PRO",
                new PlanPriceRequest(new BigDecimal("30000"), NOW.minusDays(1)), ADMIN))
                .isInstanceOf(InvalidBillingRequestException.class);
        assertThat(useCase.listPrices("PRO")).hasSize(1);
    }

    @Test
    void futurePriceIsAdded() {
        PlanPriceView view = useCase.addPrice("PRO",
                new PlanPriceRequest(new BigDecimal("30000"), NOW.plusSeconds(1)), ADMIN);

        assertThat(view.amount()).isEqualTo("30000.00");
        assertThat(view.createdBy()).isEqualTo("admin@centauro");
        assertThat(useCase.listPrices("PRO")).hasSize(2);
    }

    @Test
    void priceWithMoreThanTwoDecimalsIsRejectedInsteadOfRounded() {
        assertThatThrownBy(() -> useCase.addPrice("PRO",
                new PlanPriceRequest(new BigDecimal("30000.001"), NOW.plusDays(1)), ADMIN))
                .isInstanceOf(InvalidBillingRequestException.class);
    }

    @Test
    void actorLogIdNeverExposesTheUsername() {
        assertThat(ADMIN.logId()).isEqualTo("PLATFORM_ADMIN:42").doesNotContain("admin@centauro");
        assertThat(Actor.platformAdmin("admin@centauro").logId()).doesNotContain("admin@centauro");
    }
}
