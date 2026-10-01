-- Optional image per color/size variant. Existing catalog and order data remain untouched.
ALTER TABLE product_variants ADD COLUMN IF NOT EXISTS image_url VARCHAR(1000);
