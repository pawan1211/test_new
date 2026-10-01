-- V17: Payment events, inventory ledger, order item images, and category taxonomy

-- Add product image snapshot to order items
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS product_image_url VARCHAR(1000);

-- Payment events table for webhook idempotency and audit trail
CREATE TABLE IF NOT EXISTS payment_events (
  id UUID PRIMARY KEY,
  order_id UUID NOT NULL REFERENCES customer_orders(id) ON DELETE CASCADE,
  provider VARCHAR(30) NOT NULL,
  provider_order_id VARCHAR(160),
  provider_payment_id VARCHAR(160),
  event_type VARCHAR(60) NOT NULL,
  event_id VARCHAR(160) UNIQUE,
  status VARCHAR(40) NOT NULL,
  amount NUMERIC(12,2),
  currency VARCHAR(3),
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_payment_events_order ON payment_events(order_id);
CREATE INDEX IF NOT EXISTS idx_payment_events_provider ON payment_events(provider, provider_order_id);
CREATE INDEX IF NOT EXISTS idx_payment_events_event_id ON payment_events(event_id);

-- Inventory ledger for tracking all stock movements
CREATE TABLE IF NOT EXISTS inventory_ledger (
  id UUID PRIMARY KEY,
  variant_id UUID NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
  product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  sku VARCHAR(100) NOT NULL,
  movement_type VARCHAR(30) NOT NULL,
  quantity INTEGER NOT NULL,
  previous_stock INTEGER NOT NULL,
  new_stock INTEGER NOT NULL,
  reference_type VARCHAR(40),
  reference_id UUID,
  reason VARCHAR(200),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_inventory_ledger_variant ON inventory_ledger(variant_id);
CREATE INDEX IF NOT EXISTS idx_inventory_ledger_product ON inventory_ledger(product_id);
CREATE INDEX IF NOT EXISTS idx_inventory_ledger_created ON inventory_ledger(created_at DESC);

-- Inventory reservations (replacing the conflicting V4/V7 definitions)
CREATE TABLE IF NOT EXISTS inventory_reservations_v2 (
  id UUID PRIMARY KEY,
  order_id UUID REFERENCES customer_orders(id) ON DELETE CASCADE,
  variant_id UUID NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  status VARCHAR(20) NOT NULL DEFAULT 'RESERVED' CHECK (status IN ('RESERVED','COMMITTED','RELEASED','EXPIRED')),
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_inventory_reservations_v2_order ON inventory_reservations_v2(order_id);
CREATE INDEX IF NOT EXISTS idx_inventory_reservations_v2_variant ON inventory_reservations_v2(variant_id);
CREATE INDEX IF NOT EXISTS idx_inventory_reservations_v2_status ON inventory_reservations_v2(status, expires_at);

-- Category taxonomy for navigation
CREATE TABLE IF NOT EXISTS categories (
  id UUID PRIMARY KEY,
  gender VARCHAR(10) NOT NULL CHECK (gender IN ('MEN','WOMEN')),
  name VARCHAR(100) NOT NULL,
  slug VARCHAR(100) NOT NULL,
  parent_id UUID REFERENCES categories(id),
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(gender, slug)
);
CREATE INDEX IF NOT EXISTS idx_categories_gender ON categories(gender);
CREATE INDEX IF NOT EXISTS idx_categories_parent ON categories(parent_id);

-- Product-category associations (many-to-many)
CREATE TABLE IF NOT EXISTS product_categories (
  product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  category_id UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
  PRIMARY KEY(product_id, category_id)
);
CREATE INDEX IF NOT EXISTS idx_product_categories_category ON product_categories(category_id);

-- Add gender and subcategory columns to products
ALTER TABLE products ADD COLUMN IF NOT EXISTS gender VARCHAR(10);
ALTER TABLE products ADD COLUMN IF NOT EXISTS subcategory VARCHAR(100);

-- Shipment tracking events
CREATE TABLE IF NOT EXISTS shipment_tracking_events (
  id UUID PRIMARY KEY,
  shipment_id UUID NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
  status VARCHAR(60) NOT NULL,
  location VARCHAR(200),
  activity VARCHAR(500),
  provider_status VARCHAR(60),
  event_time TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_shipment_tracking_events_shipment ON shipment_tracking_events(shipment_id);

-- Business configuration for footer/contact
CREATE TABLE IF NOT EXISTS business_config (
  key VARCHAR(100) PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
