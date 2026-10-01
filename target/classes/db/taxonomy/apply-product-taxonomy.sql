-- Idempotent product -> taxonomy mapping.
--
-- Runs after the local catalog is published so the classification is correct
-- regardless of whether products already existed when the Flyway migrations
-- completed. Safe to execute on every startup.

-- ---------------------------------------------------------------------------
-- 1. Classify every product onto gender + subcategory slug.
--    products.category is left untouched: it backs existing product URLs,
--    order-history snapshots and free-text search.
-- ---------------------------------------------------------------------------
WITH mapping(cat, name_pattern, sub_slug) AS (VALUES
  ('Men · Accessories',              '%Cap%',                        'caps'),
  ('Men · Accessories',              '%Belt%',                       'belts'),
  ('Men · Accessories',              '%Scarf%',                      'scarves'),
  ('Men · Activewear',               '%Jogger%',                     'joggers'),
  ('Men · Activewear',               '%Track Zip%',                  'track-jackets'),
  ('Men · Bags',                     '%Crossbody%',                  'bags'),
  ('Men · Bags',                     '%Weekender%',                  'bags'),
  ('Men · Denim · Jackets',          '%Denim Trucker%',              'jackets'),
  ('Men · Denim · Jeans',            '%Relaxed%',                    'relaxed'),
  ('Men · Denim · Jeans',            '%Straight%',                   'straight'),
  ('Men · Footwear',                 '%Low-Top Sneaker%',            'sneakers'),
  ('Men · Footwear',                 '%Derby Shoe%',                 'derby-shoes'),
  ('Men · Footwear',                 '%Leather Sneaker%',            'leather-sneakers'),
  ('Men · Jackets',                  '%Field Overshirt%',            'field-jackets'),
  ('Men · Jackets',                  '%Bomber%',                     'bomber-jackets'),
  ('Men · Jackets',                  '%Blazer%',                     'blazers'),
  ('Men · Knitwear',                 '%Crew Knit%',                  'crew-knit'),
  ('Men · Knitwear',                 '%Knit Polo%',                  'polo-knit'),
  ('Men · Knitwear',                 '%Cardigan%',                   'cardigans'),
  ('Men · Shirts · Camp Collar',     '%',                            'camp-collar'),
  ('Men · Shirts · Overshirts',     '%',                            'oversized'),
  ('Men · Shirts · Oxford',          '%',                            'oxford'),
  ('Men · Shirts · Short Sleeve',    '%',                            'linen'),
  ('Men · Shorts',                   '%Drawstring%',                 'drawstring'),
  ('Men · Shorts',                   '%Knit Short%',                 'knit'),
  ('Men · T-Shirts',                 '%',                            't-shirts'),
  ('Men · Trousers',                 '%Pleated%',                    'pleated'),
  ('Men · Trousers',                 '%Chino%',                      'chinos'),
  ('Men · Trousers',                 '%Cargo%',                      'cargo'),
  ('Women · Accessories',            '%Cap%',                        'caps'),
  ('Women · Accessories',            '%Scarf%',                      'scarves'),
  ('Women · Accessories',            '%Belt%',                       'belts'),
  ('Women · Activewear',             '%Legging%',                    'leggings'),
  ('Women · Activewear',             '%Studio Zip%',                 'studio-jackets'),
  ('Women · Bags',                   '%Tote%',                       'tote'),
  ('Women · Bags',                   '%Shoulder Bag%',               'shoulder-bags'),
  ('Women · Denim · Jackets',        '%Cropped Denim Jacket%',       'jackets'),
  ('Women · Denim · Jeans',          '%Wide Leg%',                   'wide-leg'),
  ('Women · Denim · Jeans',          '%Straight%',                   'straight'),
  ('Women · Dresses',                '%Midi%',                       'midi'),
  ('Women · Dresses',                '%Occasion%',                   'occasion'),
  ('Women · Dresses',                '%Slip%',                       'slip'),
  ('Women · Dresses · Shirt Dresses','%',                            'shirt'),
  ('Women · Footwear',               '%Leather Sneaker%',            'sneakers'),
  ('Women · Footwear',               '%Ballet Flat%',                'ballet-flats'),
  ('Women · Footwear',               '%Sandal%',                     'sandals'),
  ('Women · Jackets',                '%Blazer%',                     'blazers'),
  ('Women · Jackets',                '%Utility Jacket%',             'utility-jackets'),
  ('Women · Jumpsuits',              '%Belted%',                     'belted'),
  ('Women · Knitwear',               '%Cardigan%',                   'cardigans'),
  ('Women · Knitwear',               '%Knit Polo%',                  'polo-knit'),
  ('Women · Shorts',                 '%',                            'shorts'),
  ('Women · Skirts',                 '%A-Line%',                     'a-line'),
  ('Women · Skirts',                 '%Midi%',                       'midi'),
  ('Women · Tops',                   '%',                            'everyday-tops'),
  ('Women · Tops · Blouses',         '%',                            'blouses'),
  ('Women · Tops · Occasion',        '%',                            'occasion-tops'),
  ('Women · Trousers',               '%Wide-Leg%',                   'wide-leg'),
  ('Women · Trousers',               '%Pleated%',                    'pleated')
)
UPDATE products p
SET gender = CASE WHEN p.category LIKE 'Men · %' THEN 'MEN'
                  WHEN p.category LIKE 'Women · %' THEN 'WOMEN'
                  ELSE NULL END,
    subcategory = m.sub_slug
FROM mapping m
WHERE p.category = m.cat
  AND p.name LIKE m.name_pattern
  AND EXISTS (
    SELECT 1 FROM categories c
    WHERE c.gender = CASE WHEN p.category LIKE 'Men · %' THEN 'MEN' ELSE 'WOMEN' END
      AND c.slug = m.sub_slug
  );

-- ---------------------------------------------------------------------------
-- 2. Rebuild collection links. Deleted first so a re-run is a clean rebuild
--    and no product can keep a link it no longer qualifies for.
-- ---------------------------------------------------------------------------
DELETE FROM product_categories;

INSERT INTO product_categories(product_id, category_id)
SELECT DISTINCT p.id, c.id
FROM products p
JOIN categories c
  ON c.gender = p.gender
 AND c.parent_id IS NULL
 AND (
      (c.gender = 'MEN' AND (
         (c.slug = 'denim'         AND p.category LIKE 'Men · Denim%')
      OR (c.slug = 'shirts'        AND (p.category LIKE 'Men · Shirts%' OR p.category = 'Men · T-Shirts'))
      OR (c.slug = 'trousers'      AND p.category = 'Men · Trousers')
      OR (c.slug = 'jackets-coats' AND p.category = 'Men · Jackets' AND p.name NOT LIKE '%Blazer%')
      OR (c.slug = 'suits-blazers' AND p.category = 'Men · Jackets' AND p.name LIKE '%Blazer%')
      OR (c.slug = 'knitwear'      AND p.category = 'Men · Knitwear')
      OR (c.slug = 'shorts'        AND p.category = 'Men · Shorts')
      OR (c.slug = 'activewear'    AND p.category = 'Men · Activewear')
      OR (c.slug = 'footwear'      AND p.category = 'Men · Footwear')
      OR (c.slug = 'accessories'   AND p.category IN ('Men · Accessories','Men · Bags'))
      ))
   OR (c.gender = 'WOMEN' AND (
         (c.slug = 'denim'         AND p.category LIKE 'Women · Denim%')
      OR (c.slug = 'tops-shirts'   AND p.category LIKE 'Women · Tops%')
      OR (c.slug = 'dresses'       AND p.category LIKE 'Women · Dresses%')
      OR (c.slug = 'trousers'      AND p.category IN ('Women · Trousers','Women · Shorts'))
      OR (c.slug = 'skirts'        AND p.category = 'Women · Skirts')
      OR (c.slug = 'jackets-coats' AND p.category = 'Women · Jackets')
      OR (c.slug = 'knitwear'      AND p.category = 'Women · Knitwear')
      OR (c.slug = 'jumpsuits'     AND p.category = 'Women · Jumpsuits')
      OR (c.slug = 'activewear'    AND p.category = 'Women · Activewear')
      OR (c.slug = 'footwear'      AND p.category = 'Women · Footwear')
      OR (c.slug = 'bags-accessories' AND p.category IN ('Women · Bags','Women · Accessories'))
      ))
 )
WHERE p.active = true
  AND p.gender IS NOT NULL;

-- "Clothing" is the broad collection: every apparel piece of that gender.
INSERT INTO product_categories(product_id, category_id)
SELECT DISTINCT p.id, c.id
FROM products p
JOIN categories c ON c.gender = p.gender AND c.slug = 'clothing' AND c.parent_id IS NULL
WHERE p.active = true
  AND p.gender IS NOT NULL
  AND p.category NOT IN (
    'Men · Footwear', 'Men · Accessories', 'Men · Bags',
    'Women · Footwear', 'Women · Accessories', 'Women · Bags'
  );
