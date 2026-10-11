-- ============================================================
-- V7: Planes comerciales (ARS, mensuales).
-- Los códigos de features y las claves de limits son contrato: ver AGENTS.md.
-- EMPRESA es a medida (custom_pricing): no tiene plan_prices y el monto se fija
-- por suscripción desde la plataforma.
-- ============================================================
INSERT INTO plans (code, name, description, active, public_visible, custom_pricing, sort_order, features, limits) VALUES
('INICIAL', 'Inicial', 'Ventas, caja y stock básico', TRUE, TRUE, FALSE, 10,
 '["SALES_CASH","STOCK_BASIC"]'::jsonb,
 '{"maxArcaInvoicesPerMonth":100}'::jsonb),
('NEGOCIO', 'Negocio', 'Stock avanzado y métricas de ventas', TRUE, TRUE, FALSE, 20,
 '["SALES_CASH","STOCK_ADVANCED","METRICS_SALES"]'::jsonb,
 '{"maxArcaInvoicesPerMonth":500}'::jsonb),
('PROFESIONAL', 'Profesional', 'Cuenta corriente, compras, producción y métricas completas', TRUE, TRUE, FALSE, 30,
 '["SALES_CASH","STOCK_ADVANCED","METRICS_SALES","ACCOUNTS_RECEIVABLE","PURCHASES","PRODUCTION","METRICS_FULL"]'::jsonb,
 '{"maxArcaInvoicesPerMonth":2000}'::jsonb),
('EMPRESA', 'Empresa', 'Todo lo de Profesional con métricas financieras, precio a medida', TRUE, TRUE, TRUE, 40,
 '["SALES_CASH","STOCK_ADVANCED","METRICS_SALES","ACCOUNTS_RECEIVABLE","PURCHASES","PRODUCTION","METRICS_FULL","METRICS_FINANCIAL"]'::jsonb,
 '{}'::jsonb);

INSERT INTO plan_prices (plan_id, currency, billing_period, amount, valid_from, created_by)
SELECT id, 'ARS', 'MONTHLY', price.amount, TIMESTAMPTZ '2026-01-01 00:00:00-03', 'seed'
FROM plans
JOIN (VALUES ('INICIAL', 14900.00), ('NEGOCIO', 29900.00), ('PROFESIONAL', 59900.00)) AS price (code, amount)
  ON price.code = plans.code;
