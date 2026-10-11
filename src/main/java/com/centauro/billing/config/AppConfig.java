package com.centauro.billing.config;

import com.centauro.billing.domain.policy.AccessPolicy;
import com.centauro.billing.domain.policy.AllowedActionsPolicy;
import com.centauro.billing.domain.policy.BillingTerms;
import com.centauro.billing.domain.policy.NoticePolicy;
import com.centauro.billing.domain.policy.PeriodCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public BillingTerms billingTerms(BillingProperties properties) {
        return new BillingTerms(properties.trial().days(), properties.trial().graceDays(),
                properties.grace().days(), properties.trial().planCode(), ZoneId.of(properties.zone()));
    }

    @Bean
    public AccessPolicy accessPolicy(BillingTerms terms) {
        return new AccessPolicy(terms);
    }

    @Bean
    public PeriodCalculator periodCalculator(BillingTerms terms) {
        return new PeriodCalculator(terms.zone());
    }

    @Bean
    public NoticePolicy noticePolicy(BillingTerms terms) {
        return new NoticePolicy(terms.zone());
    }

    @Bean
    public AllowedActionsPolicy allowedActionsPolicy() {
        return new AllowedActionsPolicy();
    }
}
