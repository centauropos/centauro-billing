package com.centauro.billing.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.centauro.billing.support.TestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

class PlanPriceResolutionTest {

    private final UUID planId = UUID.randomUUID();

    @Test
    void currentPriceIsTheLatestEffective() {
        Plan plan = planWith(price("10000", NOW.minusMonths(6)), price("12000", NOW.minusDays(1)),
                price("15000", NOW.plusDays(10)));

        assertThat(plan.currentPrice(NOW)).get().extracting(PlanPrice::amount)
                .isEqualTo(new BigDecimal("12000.00"));
        assertThat(plan.upcomingPrices(NOW)).extracting(PlanPrice::amount)
                .containsExactly(new BigDecimal("15000.00"));
    }

    @Test
    void futurePriceDoesNotApplyYet() {
        Plan plan = planWith(price("15000", NOW.plusSeconds(1)));

        assertThat(plan.currentPrice(NOW)).isEmpty();
    }

    @Test
    void priceEffectiveExactlyNowApplies() {
        Plan plan = planWith(price("9000", NOW));

        assertThat(plan.currentPrice(NOW)).isPresent();
    }

    @Test
    void amountsAreScaledToTwoDecimalsHalfUp() {
        assertThat(price("100.005", NOW).amount()).isEqualTo(new BigDecimal("100.01"));
    }

    private PlanPrice price(String amount, OffsetDateTime validFrom) {
        return PlanPrice.create(planId, new BigDecimal(amount), validFrom, "test");
    }

    private Plan planWith(PlanPrice... prices) {
        return new Plan(planId, "PROFESIONAL", "Profesional", null, true, true, false, 0, Set.of(), Map.of(), List.of(prices));
    }
}
