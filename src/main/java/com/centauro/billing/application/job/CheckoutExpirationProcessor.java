package com.centauro.billing.application.job;

import com.centauro.billing.domain.model.GatewayCheckout;
import com.centauro.billing.domain.port.CheckoutRepositoryPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Una transacción por checkout, con la fila bloqueada (SKIP LOCKED) y la condición reevaluada. */
@Component
public class CheckoutExpirationProcessor {

    private final CheckoutRepositoryPort checkouts;

    public CheckoutExpirationProcessor(CheckoutRepositoryPort checkouts) {
        this.checkouts = checkouts;
    }

    /** Devuelve el checkout vencido, o vacío si ya no correspondía (o lo tenía otra transacción). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<GatewayCheckout> expire(UUID checkoutId, OffsetDateTime now) {
        Optional<GatewayCheckout> locked = checkouts.findByIdForUpdateSkipLocked(checkoutId);
        if (locked.isEmpty() || !locked.get().isPending() || !locked.get().isExpired(now)) {
            return Optional.empty();
        }
        GatewayCheckout checkout = locked.get();
        checkout.markExpired(now);
        return Optional.of(checkouts.save(checkout));
    }
}
