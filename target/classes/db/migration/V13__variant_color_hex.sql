-- Optional merchandising color token used for a true catalog color swatch.
-- Color variants and inventory remain the existing product_variants records.
ALTER TABLE product_variants ADD COLUMN IF NOT EXISTS color_hex VARCHAR(7);
ALTER TABLE product_variants DROP CONSTRAINT IF EXISTS product_variants_color_hex_format;
ALTER TABLE product_variants ADD CONSTRAINT product_variants_color_hex_format
  CHECK (color_hex IS NULL OR color_hex ~ '^#[0-9A-Fa-f]{6}$');
