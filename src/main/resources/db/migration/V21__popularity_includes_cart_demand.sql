-- Fold real in-cart demand into the catalog popularity ranking.
--
-- Order history in this database belongs to the previous (now archived) catalog
-- generation, so sales alone left every live product at zero. Cart additions are
-- the only other real demand signal the platform records, so popularity is the
-- sum of units sold and units added to a cart. Both come from live behavioural
-- data; nothing here is synthesised.
--
-- Sales increments continue to be applied by CommerceController at order time.
-- Cart demand is a backfill of the signal captured so far.
UPDATE products p
   SET popularity_score = COALESCE(d.score, 0)
  FROM (
        SELECT product_id, SUM(units)::int AS score
          FROM (
                SELECT oi.product_id, SUM(oi.quantity) AS units
                  FROM order_items oi
                 WHERE oi.product_id IS NOT NULL
                 GROUP BY oi.product_id
                UNION ALL
                SELECT v.product_id, SUM(c.quantity) AS units
                  FROM storefront_cart_items c
                  JOIN product_variants v ON v.id = c.variant_id
                 WHERE v.product_id IS NOT NULL
                 GROUP BY v.product_id
               ) combined
      GROUP BY product_id
       ) d
 WHERE p.id = d.product_id;

-- Anything the seeder republished keeps a zero score rather than a stale one.
UPDATE products SET popularity_score = 0 WHERE popularity_score IS NULL;
