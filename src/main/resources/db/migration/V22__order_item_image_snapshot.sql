-- Order history must keep showing the picture the shopper actually bought.
--
-- CommerceController now snapshots product_image_url at checkout time. This
-- migration backfills rows that were written before that change, using the
-- variant image where one exists and the product image otherwise. Rows written
-- from now on carry their own snapshot and are never touched again.
--
-- The order_items.product_id / variant_id foreign keys are ON DELETE SET NULL,
-- so the stored URL -- not a live join -- is what keeps old orders intact after
-- a product is retired.

UPDATE order_items oi
SET product_image_url = COALESCE(
      (SELECT v.image_url
         FROM product_variants v
        WHERE v.id = oi.variant_id
          AND NULLIF(btrim(v.image_url), '') IS NOT NULL
        LIMIT 1),
      (SELECT p.image_url
         FROM products p
        WHERE p.id = oi.product_id
          AND NULLIF(btrim(p.image_url), '') IS NOT NULL
        LIMIT 1)
    )
WHERE NULLIF(btrim(COALESCE(oi.product_image_url, '')), '') IS NULL;
