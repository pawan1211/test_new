-- Popularity ranking for collection sorting.
-- The score is derived from real order line items, never invented, and is kept
-- current by CommerceController when an order is placed.
ALTER TABLE products ADD COLUMN IF NOT EXISTS popularity_score integer NOT NULL DEFAULT 0;

UPDATE products p
   SET popularity_score = COALESCE(s.units, 0)
  FROM (
        SELECT product_id, SUM(quantity)::int AS units
          FROM order_items
         WHERE product_id IS NOT NULL
      GROUP BY product_id
       ) s
 WHERE p.id = s.product_id
   AND p.popularity_score <> COALESCE(s.units, 0);

CREATE INDEX IF NOT EXISTS ix_products_popularity
    ON products (popularity_score DESC, created_at DESC)
 WHERE active = true;
