CREATE TABLE IF NOT EXISTS payment_transactions (
 id UUID PRIMARY KEY, order_id UUID NOT NULL REFERENCES customer_orders(id) ON DELETE CASCADE,
 provider VARCHAR(30) NOT NULL, provider_order_id VARCHAR(160) NOT NULL UNIQUE,
 amount NUMERIC(12,2) NOT NULL, currency VARCHAR(3) NOT NULL DEFAULT 'INR',
 status VARCHAR(30) NOT NULL, provider_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_payment_transactions_order ON payment_transactions(order_id,created_at DESC);
CREATE TABLE IF NOT EXISTS shipments (
 id UUID PRIMARY KEY, order_id UUID NOT NULL UNIQUE REFERENCES customer_orders(id) ON DELETE CASCADE,
 provider VARCHAR(30) NOT NULL, provider_order_id VARCHAR(160), shipment_id VARCHAR(160), awb_code VARCHAR(100),
 courier_name VARCHAR(160), status VARCHAR(40) NOT NULL DEFAULT 'CREATED', tracking_url TEXT,
 provider_payload JSONB NOT NULL DEFAULT '{}'::jsonb, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS customer_users (
 id UUID PRIMARY KEY, full_name VARCHAR(160) NOT NULL, email VARCHAR(254) NOT NULL UNIQUE,
 phone VARCHAR(30), password_hash VARCHAR(100) NOT NULL, role VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER',
 enabled BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS customer_addresses (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES customer_users(id) ON DELETE CASCADE,
 recipient_name VARCHAR(160) NOT NULL, phone VARCHAR(30) NOT NULL, address_line1 VARCHAR(250) NOT NULL,
 address_line2 VARCHAR(250), city VARCHAR(100) NOT NULL, state VARCHAR(100) NOT NULL, postal_code VARCHAR(20) NOT NULL,
 country VARCHAR(2) NOT NULL DEFAULT 'IN', is_default BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
