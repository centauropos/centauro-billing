package com.centauro.billing.application.dto;

import jakarta.validation.constraints.Size;

public record CancelRequest(@Size(max = 1000) String reason) {
}