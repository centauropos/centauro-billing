-- ============================================================
-- V3: Historial de eventos de la suscripción
-- ============================================================
CREATE TABLE subscription_events (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id  UUID         NOT NULL REFERENCES subscriptions (id),
    tenant_id        VARCHAR(64)  NOT NULL,
    type             VARCHAR(50)  NOT NULL,
    from_status      VARCHAR(20),
    to_status        VARCHAR(20),
    actor_type       VARCHAR(20)  NOT NULL,
    actor            VARCHAR(255),
    reason           TEXT,
    data             JSONB,
    dedupe_key       VARCHAR(150),
    occurred_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_subscription_events_actor CHECK (
        actor_type IN ('SYSTEM','JOB','WEBHOOK','TENANT_USER','PLATFORM_ADMIN','INTERNAL'))
);

CREATE UNIQUE INDEX uq_subscription_events_dedupe
    ON subscription_events (subscription_id, dedupe_key) WHERE dedupe_key IS NOT NULL;
CREATE INDEX idx_subscription_events_sub ON subscription_events (subscription_id, occurred_at DESC);
CREATE INDEX idx_subscription_events_tenant ON subscription_events (tenant_id, occurred_at DESC);
