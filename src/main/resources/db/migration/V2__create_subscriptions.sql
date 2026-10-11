-- ============================================================
-- V2: Suscripción (una por tenant)
-- ============================================================
CREATE TABLE subscriptions (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                VARCHAR(64)   NOT NULL,
    plan_id                  UUID          NOT NULL REFERENCES plans (id),
    status                   VARCHAR(20)   NOT NULL,
    suspension_reason        VARCHAR(30),
    past_due_reason          VARCHAR(30),
    trial_ends_at            TIMESTAMPTZ,
    current_period_start     TIMESTAMPTZ,
    current_period_end       TIMESTAMPTZ,
    grace_ends_at            TIMESTAMPTZ,
    cancel_at_period_end     BOOLEAN       NOT NULL DEFAULT FALSE,
    canceled_at              TIMESTAMPTZ,
    suspended_at             TIMESTAMPTZ,
    price_amount             NUMERIC(12,2),
    currency                 CHAR(3)       NOT NULL DEFAULT 'ARS',
    gateway                  VARCHAR(20),
    gateway_subscription_id  VARCHAR(64),
    gateway_status           VARCHAR(30),
    gateway_last_modified    TIMESTAMPTZ,
    next_charge_at           TIMESTAMPTZ,
    billing_email            VARCHAR(255),
    company_name             VARCHAR(255),
    version                  BIGINT        NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_subscriptions_tenant UNIQUE (tenant_id),
    CONSTRAINT ck_subscriptions_tenant_format CHECK (tenant_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    CONSTRAINT ck_subscriptions_status CHECK (status IN ('TRIALING','ACTIVE','PAST_DUE','SUSPENDED','CANCELED')),
    CONSTRAINT ck_subscriptions_suspension_reason CHECK (
        suspension_reason IS NULL OR suspension_reason IN ('NON_PAYMENT','ADMIN_READ_ONLY','ADMIN_BLOCK')),
    CONSTRAINT ck_subscriptions_suspended_has_reason CHECK ((status = 'SUSPENDED') = (suspension_reason IS NOT NULL)),
    CONSTRAINT ck_subscriptions_past_due_reason CHECK (
        past_due_reason IS NULL OR past_due_reason IN ('TRIAL_EXPIRED','PERIOD_UNPAID')),
    CONSTRAINT ck_subscriptions_trial CHECK (status <> 'TRIALING' OR trial_ends_at IS NOT NULL),
    CONSTRAINT ck_subscriptions_past_due CHECK (status <> 'PAST_DUE' OR (grace_ends_at IS NOT NULL AND past_due_reason IS NOT NULL)),
    CONSTRAINT ck_subscriptions_active CHECK (status <> 'ACTIVE' OR current_period_end IS NOT NULL),
    CONSTRAINT ck_subscriptions_currency CHECK (currency = 'ARS'),
    CONSTRAINT ck_subscriptions_price CHECK (price_amount IS NULL OR price_amount >= 0),
    CONSTRAINT ck_subscriptions_gateway CHECK (gateway IS NULL OR gateway IN ('MERCADOPAGO','MANUAL'))
);

CREATE UNIQUE INDEX uq_subscriptions_gateway_sub
    ON subscriptions (gateway, gateway_subscription_id) WHERE gateway_subscription_id IS NOT NULL;
CREATE INDEX idx_subscriptions_status       ON subscriptions (status);
CREATE INDEX idx_subscriptions_trial_due    ON subscriptions (trial_ends_at)      WHERE status = 'TRIALING';
CREATE INDEX idx_subscriptions_period_due   ON subscriptions (current_period_end) WHERE status = 'ACTIVE';
CREATE INDEX idx_subscriptions_grace_due    ON subscriptions (grace_ends_at)      WHERE status = 'PAST_DUE';
