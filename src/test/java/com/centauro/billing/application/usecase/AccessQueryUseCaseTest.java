package com.centauro.billing.application.usecase;

import com.centauro.billing.application.dto.AccessView;
import com.centauro.billing.application.service.SubscriptionViewAssembler;
import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.support.TestFixtures;
import com.centauro.billing.support.InMemoryPlanRepository;
import com.centauro.billing.support.InMemorySubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.clockAt;
import static com.centauro.billing.support.TestFixtures.customPlan;
import static com.centauro.billing.support.TestFixtures.plan;
import static com.centauro.billing.support.TestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;

class AccessQueryUseCaseTest {

    private InMemorySubscriptionRepository subscriptions;
    private InMemoryPlanRepository plans;
    private AccessQueryUseCase useCase;

    @BeforeEach
    void setUp() {
        subscriptions = new InMemorySubscriptionRepository();
        plans = new InMemoryPlanRepository();
        useCase = new AccessQueryUseCase(subscriptions, plans, TestFixtures.assembler(), clockAt(NOW));
    }

    @Test
    void exposesFeaturesAndLimitsOfTheSubscribedPlan() {
        Plan inicial = plans.put(plan("INICIAL", true, new BigDecimal("14900")));
        subscriptions.put(subscription().planId(inicial.getId()).build());

        AccessView view = useCase.access("tenant-1");

        assertThat(view.known()).isTrue();
        assertThat(view.planCode()).isEqualTo("INICIAL");
        assertThat(view.features()).containsExactly("SALES_CASH");
        assertThat(view.limits()).containsEntry("maxArcaInvoicesPerMonth", 100);
    }

    @Test
    void customPlanWithoutLimitsExposesAnEmptyMap() {
        Plan empresa = plans.put(customPlan("EMPRESA", true));
        subscriptions.put(subscription().planId(empresa.getId()).build());

        AccessView view = useCase.access("tenant-1");

        assertThat(view.features()).contains("METRICS_FINANCIAL");
        assertThat(view.limits()).isEmpty();
    }

    @Test
    void unknownTenantHasNoPlanAndEmptyFeaturesAndLimits() {
        AccessView view = useCase.access("tenant-desconocido");

        assertThat(view.known()).isFalse();
        assertThat(view.planCode()).isNull();
        assertThat(view.features()).isEmpty();
        assertThat(view.limits()).isEmpty();
    }
}
