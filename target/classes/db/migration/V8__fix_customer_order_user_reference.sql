CREATE TABLE IF NOT EXISTS return_requests (
 id UUID PRIMARY KEY, order_id UUID NOT NULL REFERENCES customer_orders(id), user_id UUID REFERENCES customer_users(id),
 reason VARCHAR(500) NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'REQUESTED', refund_amount NUMERIC(12,2),
 refund_reference VARCHAR(160), admin_note TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_returns_order ON return_requests(order_id,created_at DESC);
CREATE TABLE IF NOT EXISTS inventory_reservations (
 id UUID PRIMARY KEY, order_id UUID NOT NULL REFERENCES customer_orders(id) ON DELETE CASCADE,
 variant_id UUID NOT NULL REFERENCES product_variants(id), quantity INT NOT NULL CHECK(quantity>0),
 status VARCHAR(20) NOT NULL DEFAULT 'RESERVED', expires_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_inventory_reservation_expiry ON inventory_reservations(status,expires_at);
