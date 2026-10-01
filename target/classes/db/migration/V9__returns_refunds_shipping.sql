-- Return/refund lifecycle and provider idempotency fields.
ALTER TABLE return_requests ADD COLUMN IF NOT EXISTS cashfree_refund_id VARCHAR(160);
ALTER TABLE return_requests ADD COLUMN IF NOT EXISTS refund_initiated_at TIMESTAMPTZ;
ALTER TABLE return_requests ADD COLUMN IF NOT EXISTS pickup_shipment_id VARCHAR(160);
ALTER TABLE return_requests ADD COLUMN IF NOT EXISTS pickup_awb VARCHAR(160);
ALTER TABLE return_requests ADD COLUMN IF NOT EXISTS pickup_tracking_url TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_return_cashfree_refund_id ON return_requests(cashfree_refund_id) WHERE cashfree_refund_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_return_status_created ON return_requests(status,created_at DESC);
