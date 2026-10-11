package com.centauro.billing.infrastructure.mercadopago;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * {@code billing.mercadopago.access-token} con texto. {@code @ConditionalOnProperty} no sirve: la propiedad
 * existe siempre ({@code ${MP_ACCESS_TOKEN:}}) y un valor vacío cuenta como presente.
 */
public class MercadoPagoConfiguredCondition implements Condition {

    static final String ACCESS_TOKEN = "billing.mercadopago.access-token";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return StringUtils.hasText(context.getEnvironment().getProperty(ACCESS_TOKEN));
    }
}
