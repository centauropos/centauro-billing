package com.centauro.billing.domain.vo;

import com.centauro.billing.domain.enums.ActorType;

import java.util.Objects;

/**
 * Quién ejecuta una operación. {@code name} se persiste en eventos y auditoría; {@code id} es el
 * identificador opaco del usuario (claim {@code id} del JWT) y es lo único que va a los logs.
 */
public record Actor(ActorType type, String name, String id) {

    public Actor {
        Objects.requireNonNull(type, "type");
    }

    public Actor(ActorType type, String name) {
        this(type, name, null);
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

    public static Actor tenantUser(String subject, String id) {
        return new Actor(ActorType.TENANT_USER, subject, id);
    }

    public static Actor platformAdmin(String subject) {
        return new Actor(ActorType.PLATFORM_ADMIN, subject);
    }

    public static Actor platformAdmin(String subject, String id) {
        return new Actor(ActorType.PLATFORM_ADMIN, subject, id);
    }

    /** Identificador apto para logs: nunca el username ni el email. */
    public String logId() {
        if (type == ActorType.JOB || type == ActorType.INTERNAL) {
            return type + ":" + name;
        }
        return type + ":" + (id == null ? "?" : id);
    }
}
