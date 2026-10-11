package com.centauro.billing.domain.vo;

import com.centauro.billing.domain.enums.ActorType;

import java.util.Objects;

public record Actor(ActorType type, String name) {

    public Actor {
        Objects.requireNonNull(type, "type");
    }

    public static Actor job(String jobName) {
        return new Actor(ActorType.JOB, jobName);
    }

    public static Actor internal(String source) {
        return new Actor(ActorType.INTERNAL, source);
    }

    public static Actor tenantUser(String subject) {
        return new Actor(ActorType.TENANT_USER, subject);
    }

    public static Actor platformAdmin(String subject) {
        return new Actor(ActorType.PLATFORM_ADMIN, subject);
    }
}
