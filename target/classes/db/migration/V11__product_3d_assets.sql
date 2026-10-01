-- Metadata for externally hosted, genuinely sourced product models. Binary files stay in object storage.
CREATE TABLE product_3d_assets (
  id UUID PRIMARY KEY,
  product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  variant_id UUID REFERENCES product_variants(id) ON DELETE CASCADE,
  asset_url VARCHAR(1200) NOT NULL,
  preview_image_url VARCHAR(1200),
  status VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','processing','ready','failed')),
  source VARCHAR(240),
  license_name VARCHAR(240),
  license_reference VARCHAR(1200),
  processing_error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (asset_url ~* '^https://'),
  CHECK (preview_image_url IS NULL OR preview_image_url ~* '^https://')
);
CREATE INDEX idx_product_3d_assets_product ON product_3d_assets(product_id, status);
CREATE UNIQUE INDEX uq_product_3d_asset_scope ON product_3d_assets(product_id, COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid));
