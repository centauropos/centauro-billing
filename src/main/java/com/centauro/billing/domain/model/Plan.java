package com.centauro.billing.domain.model;

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
    private int sortOrder;
    private Set<String> features;
    /** Un valor {@code null} significa ilimitado. */
    private Map<String, Integer> limits;
    private final List<PlanPrice> prices;

    public Plan(UUID id, String code, String name, String description, boolean active, boolean publicVisible,
                int sortOrder, Set<String> features, Map<String, Integer> limits, List<PlanPrice> prices) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.description = description;
        this.active = active;
        this.publicVisible = publicVisible;
        this.sortOrder = sortOrder;
        this.features = features == null ? new LinkedHashSet<>() : new LinkedHashSet<>(features);
        this.limits = limits == null ? new LinkedHashMap<>() : new LinkedHashMap<>(limits);
        this.prices = prices == null ? new ArrayList<>() : new ArrayList<>(prices);
    }

    public static Plan create(String code, String name, String description, boolean active, boolean publicVisible,
                              int sortOrder, Set<String> features, Map<String, Integer> limits) {
        return new Plan(UUID.randomUUID(), code, name, description, active, publicVisible, sortOrder,
                features, limits, List.of());
    }

    public void update(String name, String description, boolean active, boolean publicVisible, int sortOrder,
                       Set<String> features, Map<String, Integer> limits) {
        this.name = name;
        this.description = description;
        this.active = active;
        this.publicVisible = publicVisible;
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
