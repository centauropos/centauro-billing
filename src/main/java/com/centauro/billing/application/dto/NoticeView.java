package com.centauro.billing.application.dto;

import com.centauro.billing.domain.model.Notice;

public record NoticeView(String code, String severity, Integer daysLeft) {

    public static NoticeView from(Notice notice) {
        return notice == null ? null
                : new NoticeView(notice.code().name(), notice.severity().name(), notice.daysLeft());
    }
}