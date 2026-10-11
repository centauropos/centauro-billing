package com.centauro.billing.domain.model;

import com.centauro.billing.domain.exception.InvalidBillingRequestException;
import com.centauro.billing.domain.vo.Amounts;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class Plan {

    public static final Pattern CODE_FORMAT = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    private final UUID id;
    private final String code;
    private String name;
    private String description;
    private boolean active;
    private boolean publicVisible;
    /** Precio a medida: el plan no tiene precios versionados y el monto se fija por suscripción. */
    private boolean customPricing;
    private int sortOrder;
    private Set<String> features;
    /** Un valor {@code null} significa ilimitado. */
    private Map<String, Integer> limits;
    private final List<PlanPrice> prices;

    public Plan(UUID id, String code, String name, String description, boolean active, boolean publicVisible,
                boolean customPricing, int sortOrder, Set<String> features, Map<String, Integer> limits,
                List<PlanPrice> prices) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.description = description;
        this.active = active;
        this.publicVisible = publicVisible;
        this.customPricing = customPricing;
        this.sortOrder = sortOrder;
        this.features = features == null ? new LinkedHashSet<>() : new LinkedHashSet<>(features);
        this.limits = limits == null ? new LinkedHashMap<>() : new LinkedHashMap<>(limits);
        this.prices = prices == null ? new ArrayList<>() : new ArrayList<>(prices);
    }

    public static Plan create(String code, String name, String description, boolean active, boolean publicVisible,
                              boolean customPricing, int sortOrder, Set<String> features,
                              Map<String, Integer> limits) {
        return new Plan(UUID.randomUUID(), code, name, description, active, publicVisible, customPricing, sortOrder,
                features, limits, List.of());
    }

    public void update(String name, String description, boolean active, boolean publicVisible,
                       boolean customPricing, int sortOrder, Set<String> features, Map<String, Integer> limits) {
        this.name = name;
        this.description = description;
        this.active = active;
        this.publicVisible = publicVisible;
        this.customPricing = customPricing;
        this.sortOrder = sortOrder;
        this.features = features == null ? new LinkedHashSet<>() : new LinkedHashSet<>(features);
        this.limits = limits == null ? new LinkedHashMap<>() : new LinkedHashMap<>(limits);
    }

    /** Precio con mayor {@code validFrom <= now}. */
    public Optional<PlanPrice> currentPrice(OffsetDateTime now) {
        return prices.stream()
                .filter(p -> p.isEffectiveAt(now))
                .max(Comparator.comparing(PlanPrice::validFrom));
    }

    public List<PlanPrice> upcomingPrices(OffsetDateTime now) {
        return prices.stream()
                .filter(p -> p.validFrom().isAfter(now))
                .sorted(Comparator.comparing(PlanPrice::validFrom))
                .toList();
    }

    /**
     * Monto a medida para una suscripción a este plan: obligatorio si el plan es a medida y prohibido si no.
     * Devuelve vacío para planes con precio de lista.
     */
    public Optional<BigDecimal> resolveCustomAmount(BigDecimal customAmount) {
        if (!customPricing) {
            if (customAmount != null) {
                throw new InvalidBillingRequestException(
                        "customAmount solo se admite para planes con precio a medida");
            }
            return Optional.empty();
        }
        if (customAmount == null) {
            throw new InvalidBillingRequestException(
                    "El plan " + code + " tiene precio a medida: indicar customAmount");
        }
        return Optional.of(Amounts.requirePositiveMoney(customAmount));
    }

    public boolean isOfferable() {
        return active && publicVisible;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isPublicVisible() {
        return publicVisible;
    }

    public boolean isCustomPricing() {
        return customPricing;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Set<String> getFeatures() {
        return features;
    }

    public Map<String, Integer> getLimits() {
        return limits;
    }

    public List<PlanPrice> getPrices() {
        return List.copyOf(prices);
    }
}
