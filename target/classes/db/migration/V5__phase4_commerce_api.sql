-- Phase 4: durable anonymous session carts + order API support. Additive only.
CREATE TABLE IF NOT EXISTS storefront_sessions (
 id UUID PRIMARY KEY, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS storefront_cart_items (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), session_id UUID NOT NULL REFERENCES storefront_sessions(id) ON DELETE CASCADE,
 variant_id UUID NOT NULL REFERENCES product_variants(id) ON DELETE RESTRICT, quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 20),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(session_id,variant_id)
);
CREATE INDEX IF NOT EXISTS idx_storefront_cart_session ON storefront_cart_items(session_id);
CREATE INDEX IF NOT EXISTS idx_reservations_active_expiry ON inventory_reservations(status,expires_at);
