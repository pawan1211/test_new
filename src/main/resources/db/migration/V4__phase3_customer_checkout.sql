-- Phase 3 additive migration. Keeps all Phase 1/2 tables and data intact.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Login sessions use opaque random tokens; only SHA-256 hashes are persisted.
CREATE TABLE IF NOT EXISTS auth_sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
  token_hash CHAR(64) NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_user ON auth_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_expiry ON auth_sessions(expires_at);

-- Prevent more than one default address per customer.
CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_default_address
 ON customer_addresses(user_id) WHERE is_default = TRUE;

-- Cart quantity bounds and checkout idempotency.
ALTER TABLE cart_items DROP CONSTRAINT IF EXISTS cart_items_quantity_check;
ALTER TABLE cart_items ADD CONSTRAINT cart_items_quantity_check CHECK (quantity BETWEEN 1 AND 20);
ALTER TABLE customer_orders ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(120);
CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_orders_idempotency
 ON customer_orders(user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- Reservation ledger supports time-bound inventory holds during checkout.
CREATE TABLE IF NOT EXISTS inventory_reservations (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 variant_id UUID NOT NULL REFERENCES product_variants(id) ON DELETE RESTRICT,
 quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 20),
 status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','CONSUMED','RELEASED','EXPIRED')),
 expires_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_reservations_variant_status_expiry
 ON inventory_reservations(variant_id,status,expires_at);
CREATE INDEX IF NOT EXISTS idx_reservations_user ON inventory_reservations(user_id,created_at DESC);
