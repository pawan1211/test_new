ALTER TABLE products
  ADD COLUMN IF NOT EXISTS product_type VARCHAR(16) NOT NULL DEFAULT 'STANDARD',
  ADD COLUMN IF NOT EXISTS garment_type VARCHAR(40),
  ADD COLUMN IF NOT EXISTS brand VARCHAR(120),
  ADD COLUMN IF NOT EXISTS collection_name VARCHAR(160);
ALTER TABLE products DROP CONSTRAINT IF EXISTS products_product_type_check;
ALTER TABLE products ADD CONSTRAINT products_product_type_check
  CHECK (product_type IN ('STANDARD','THREE_D'));

ALTER TABLE product_3d_assets
  ADD COLUMN IF NOT EXISTS original_filename VARCHAR(255),
  ADD COLUMN IF NOT EXISTS file_format VARCHAR(8) NOT NULL DEFAULT 'glb',
  ADD COLUMN IF NOT EXISTS file_size_bytes BIGINT,
  ADD COLUMN IF NOT EXISTS model_version VARCHAR(80) NOT NULL DEFAULT '1',
  ADD COLUMN IF NOT EXISTS material_configuration JSONB NOT NULL DEFAULT '{}'::jsonb,
  ADD COLUMN IF NOT EXISTS supported_colors JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE product_3d_assets DROP CONSTRAINT IF EXISTS product_3d_assets_file_size_supported;
ALTER TABLE product_3d_assets ADD CONSTRAINT product_3d_assets_file_size_supported
  CHECK (file_size_bytes IS NULL OR file_size_bytes BETWEEN 1 AND 104857600);
ALTER TABLE product_3d_assets DROP CONSTRAINT IF EXISTS product_3d_assets_format_supported;
ALTER TABLE product_3d_assets ADD CONSTRAINT product_3d_assets_format_supported
  CHECK (file_format IN ('glb','gltf'));
ALTER TABLE product_3d_assets DROP CONSTRAINT IF EXISTS product_3d_assets_material_config_object;
ALTER TABLE product_3d_assets ADD CONSTRAINT product_3d_assets_material_config_object
  CHECK (jsonb_typeof(material_configuration) = 'object');
ALTER TABLE product_3d_assets DROP CONSTRAINT IF EXISTS product_3d_assets_supported_colors_array;
ALTER TABLE product_3d_assets ADD CONSTRAINT product_3d_assets_supported_colors_array
  CHECK (jsonb_typeof(supported_colors) = 'array');
ALTER TABLE product_3d_assets DROP CONSTRAINT IF EXISTS product_3d_assets_asset_url_check;
ALTER TABLE product_3d_assets DROP CONSTRAINT IF EXISTS product_3d_assets_preview_image_url_check;
ALTER TABLE product_3d_assets ADD CONSTRAINT product_3d_asset_url_supported
  CHECK (asset_url ~* '^https://' OR asset_url ~ '^http://localhost(:[0-9]+)?/assets/');
ALTER TABLE product_3d_assets ADD CONSTRAINT product_3d_preview_url_supported
  CHECK (preview_image_url IS NULL OR preview_image_url ~* '^https://' OR preview_image_url ~ '^http://localhost(:[0-9]+)?/assets/');

ALTER TABLE product_variants ADD COLUMN IF NOT EXISTS model_asset_id UUID REFERENCES product_3d_assets(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_product_variants_model_asset ON product_variants(model_asset_id);
