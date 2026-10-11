package com.centauro.billing.domain.model;

import com.centauro.billing.domain.enums.NoticeCode;
import com.centauro.billing.domain.enums.NoticeSeverity;

public record Notice(NoticeCode code, NoticeSeverity severity, Integer daysLeft) {
}
