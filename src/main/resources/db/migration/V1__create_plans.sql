-- ============================================================
-- V1: Planes y precios versionados
-- ============================================================
CREATE TABLE plans (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(40)  NOT NULL,
    name            VARCHAR(100) NOT NULL,
    description     TEXT,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    public_visible  BOOLEAN      NOT NULL DEFAULT FALSE,
    custom_pricing  BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order      INTEGER      NOT NULL DEFAULT 0,
    features        JSONB        NOT NULL DEFAULT '[]'::jsonb,
    limits          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_plans_code UNIQUE (code),
    CONSTRAINT ck_plans_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT ck_plans_features_array CHECK (jsonb_typeof(features) = 'array'),
    CONSTRAINT ck_plans_limits_object CHECK (jsonb_typeof(limits) = 'object')
);

CREATE TABLE plan_prices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id         UUID          NOT NULL REFERENCES plans (id),
    currency        CHAR(3)       NOT NULL DEFAULT 'ARS',
    billing_period  VARCHAR(10)   NOT NULL DEFAULT 'MONTHLY',
    amount          NUMERIC(12,2) NOT NULL,
    valid_from      TIMESTAMPTZ   NOT NULL,
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_plan_prices_currency CHECK (currency = 'ARS'),
    CONSTRAINT ck_plan_prices_period CHECK (billing_period = 'MONTHLY'),
    CONSTRAINT ck_plan_prices_amount CHECK (amount > 0),
    CONSTRAINT uq_plan_prices_validity UNIQUE (plan_id, currency, billing_period, valid_from)
);

CREATE INDEX idx_plan_prices_lookup ON plan_prices (plan_id, currency, billing_period, valid_from DESC);
