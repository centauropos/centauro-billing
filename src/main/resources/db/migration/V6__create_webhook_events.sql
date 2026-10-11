-- ============================================================
-- V6: Bandeja de entrada de webhooks (la usa Mercado Pago a partir de PR1b)
-- ============================================================
CREATE TABLE webhook_events (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider         VARCHAR(20)  NOT NULL,
    dedupe_key       VARCHAR(150) NOT NULL,
    notification_id  VARCHAR(64),
    request_id       VARCHAR(128),
    topic            VARCHAR(60),
    action           VARCHAR(60),
    resource_id      VARCHAR(64),
    live_mode        BOOLEAN,
    payload          JSONB        NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    attempts         INTEGER      NOT NULL DEFAULT 0,
    last_error       TEXT,
    next_attempt_at  TIMESTAMPTZ,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at     TIMESTAMPTZ,

    CONSTRAINT uq_webhook_events_dedupe UNIQUE (provider, dedupe_key),
    CONSTRAINT ck_webhook_events_provider CHECK (provider = 'MERCADOPAGO'),
    CONSTRAINT ck_webhook_events_status CHECK (status IN ('RECEIVED','PROCESSED','IGNORED','FAILED'))
);

CREATE INDEX idx_webhook_events_retry ON webhook_events (next_attempt_at) WHERE status IN ('RECEIVED','FAILED');
CREATE INDEX idx_webhook_events_resource ON webhook_events (topic, resource_id);
