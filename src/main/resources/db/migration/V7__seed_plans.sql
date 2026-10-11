-- ============================================================
-- V7: Planes de ejemplo.
-- PLACEHOLDER: nombres, features, límites y PRECIOS son de ejemplo. EDITAR antes de aplicar
-- en producción, o cargar precios reales desde el backoffice (nuevo plan_price con valid_from).
-- Los planes nacen con public_visible = FALSE: el checkout rechaza planes no visibles,
-- así nadie paga un precio placeholder. Se publican desde el backoffice.
-- ============================================================
INSERT INTO plans (code, name, description, active, public_visible, sort_order, features, limits) VALUES
('BASICO', 'Básico', 'Un local, facturación y stock', TRUE, FALSE, 10,
 '["POS","AFIP_INVOICING","STOCK","CLIENTS"]'::jsonb,
 '{"maxBranches":1,"maxTerminals":2,"maxUsers":3}'::jsonb),
('PRO', 'Pro', 'Multi sucursal, compras y reportes', TRUE, FALSE, 20,
 '["POS","AFIP_INVOICING","STOCK","CLIENTS","PURCHASES","MULTI_BRANCH","REPORTS_ADVANCED","DASHBOARD_CUSTOM"]'::jsonb,
 '{"maxBranches":5,"maxTerminals":10,"maxUsers":15}'::jsonb);

INSERT INTO plan_prices (plan_id, currency, billing_period, amount, valid_from, created_by)
SELECT id, 'ARS', 'MONTHLY', 10000.00 /* PLACEHOLDER: EDITAR */, TIMESTAMPTZ '2026-01-01 00:00:00-03', 'seed'
FROM plans WHERE code = 'BASICO';

INSERT INTO plan_prices (plan_id, currency, billing_period, amount, valid_from, created_by)
SELECT id, 'ARS', 'MONTHLY', 25000.00 /* PLACEHOLDER: EDITAR */, TIMESTAMPTZ '2026-01-01 00:00:00-03', 'seed'
FROM plans WHERE code = 'PRO';
