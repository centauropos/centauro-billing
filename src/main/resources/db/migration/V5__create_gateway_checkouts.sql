-- ============================================================
-- V5: Checkouts de la pasarela (los usa Mercado Pago a partir de PR1b)
-- ============================================================
CREATE TABLE gateway_checkouts (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id         UUID          NOT NULL REFERENCES subscriptions (id),
    tenant_id               VARCHAR(64)   NOT NULL,
    plan_id                 UUID          NOT NULL REFERENCES plans (id),
    price_amount            NUMERIC(12,2) NOT NULL,
    currency                CHAR(3)       NOT NULL DEFAULT 'ARS',
    gateway                 VARCHAR(20)   NOT NULL DEFAULT 'MERCADOPAGO',
    gateway_preapproval_id  VARCHAR(64),
    init_point              TEXT,
    payer_email             VARCHAR(255)  NOT NULL,
    status                  VARCHAR(20)   NOT NULL,
    start_date              TIMESTAMPTZ,
    expires_at              TIMESTAMPTZ   NOT NULL,
    requested_by            VARCHAR(255),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_checkouts_status CHECK (status IN ('PENDING','AUTHORIZED','EXPIRED','CANCELLED','SUPERSEDED','FAILED')),
    CONSTRAINT ck_checkouts_amount CHECK (price_amount > 0),
    CONSTRAINT ck_checkouts_gateway CHECK (gateway = 'MERCADOPAGO')
);

CREATE UNIQUE INDEX uq_checkouts_preapproval ON gateway_checkouts (gateway, gateway_preapproval_id) WHERE gateway_preapproval_id IS NOT NULL;
CREATE UNIQUE INDEX uq_checkouts_one_pending ON gateway_checkouts (subscription_id) WHERE status = 'PENDING';
CREATE INDEX idx_checkouts_expiration ON gateway_checkouts (expires_at) WHERE status = 'PENDING';
