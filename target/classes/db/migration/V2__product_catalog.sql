CREATE TABLE products (
 id UUID PRIMARY KEY, slug VARCHAR(180) NOT NULL UNIQUE, name VARCHAR(240) NOT NULL,
 description VARCHAR(3000), category VARCHAR(100) NOT NULL,
 price NUMERIC(12,2) NOT NULL CHECK(price >= 0), mrp NUMERIC(12,2) CHECK(mrp >= 0),
 image_url VARCHAR(1000), active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_products_active_category ON products(active, category);
CREATE INDEX idx_products_created_at ON products(created_at DESC);
