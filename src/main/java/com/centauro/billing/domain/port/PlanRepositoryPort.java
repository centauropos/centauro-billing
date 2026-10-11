package com.centauro.billing.domain.port;

import com.centauro.billing.domain.model.Plan;
import com.centauro.billing.domain.model.PlanPrice;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanRepositoryPort {

    Optional<Plan> findByCode(String code);

    Optional<Plan> findById(UUID id);

    /** Todos los planes con sus precios, ordenados por {@code sortOrder} y código. */
    List<Plan> findAll();

    boolean existsByCode(String code);

    /** Persiste los datos del plan; los precios se guardan con {@link #savePrice}. */
    Plan save(Plan plan);

    PlanPrice savePrice(PlanPrice price);

    boolean existsPrice(UUID planId, OffsetDateTime validFrom);

    Optional<PlanPrice> findPrice(UUID priceId);

    void deletePrice(UUID priceId);
}