package com.centauro.billing.application.dto;

import java.time.OffsetDateTime;

public record TrialResult(String tenantId, String status, String planCode, OffsetDateTime trialEndsAt,
                          boolean created) {
}