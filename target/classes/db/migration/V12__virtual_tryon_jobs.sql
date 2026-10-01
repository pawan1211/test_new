CREATE TABLE virtual_tryon_jobs (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES customer_users(id) ON DELETE CASCADE,
  product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  variant_id UUID REFERENCES product_variants(id) ON DELETE SET NULL,
  status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','PROCESSING','COMPLETED','FAILED','EXPIRED')),
  status_message VARCHAR(300),
  provider_event_id VARCHAR(160),
  result_path VARCHAR(1200),
  consented_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_virtual_tryon_jobs_owner_created ON virtual_tryon_jobs(user_id, created_at DESC);
CREATE INDEX idx_virtual_tryon_jobs_expiry ON virtual_tryon_jobs(expires_at) WHERE status IN ('QUEUED','PROCESSING','COMPLETED');
