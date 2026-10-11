-- ============================================================
-- V4: Pagos (Mercado Pago y manuales)
-- ============================================================
CREATE TABLE payments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id     UUID          NOT NULL REFERENCES subscriptions (id),
    tenant_id           VARCHAR(64)   NOT NULL,
    source              VARCHAR(20)   NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    amount              NUMERIC(12,2) NOT NULL,
    currency            CHAR(3)       NOT NULL DEFAULT 'ARS',
    gateway_payment_id  VARCHAR(64),
    gateway_charge_id   VARCHAR(64),
    gateway_status      VARCHAR(40),
    period_start        TIMESTAMPTZ,
    period_end          TIMESTAMPTZ,
    paid_at             TIMESTAMPTZ,
    applied             BOOLEAN       NOT NULL DEFAULT FALSE,
    reference           VARCHAR(255),
    notes               TEXT,
    registered_by       VARCHAR(255),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_payments_source CHECK (source IN ('MERCADOPAGO','MANUAL')),
    CONSTRAINT ck_payments_status CHECK (status IN ('APPROVED','REJECTED','PENDING','CANCELLED','REFUNDED')),
    CONSTRAINT ck_payments_amount CHECK (amount >= 0),
    CONSTRAINT ck_payments_currency CHECK (currency = 'ARS'),
    CONSTRAINT ck_payments_gateway_id CHECK (source = 'MANUAL' OR gateway_payment_id IS NOT NULL),
    CONSTRAINT ck_payments_period CHECK (period_end IS NULL OR period_start IS NULL OR period_end > period_start)
);

CREATE UNIQUE INDEX uq_payments_gateway ON payments (source, gateway_payment_id) WHERE gateway_payment_id IS NOT NULL;
CREATE UNIQUE INDEX uq_payments_manual_reference ON payments (tenant_id, reference) WHERE source = 'MANUAL' AND reference IS NOT NULL;
CREATE INDEX idx_payments_subscription ON payments (subscription_id, created_at DESC);
