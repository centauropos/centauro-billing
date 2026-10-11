package com.centauro.billing.application.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PlanPriceView(UUID id, String amount, String currency, String period, OffsetDateTime validFrom,
                            String createdBy) {
}