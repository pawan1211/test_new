-- V18: Canonical MEN/WOMEN taxonomy + mapping of the existing catalog onto it.
--
-- Design rules honoured here:
--   * One canonical row per category. No spelling/plural duplicates.
--   * "Shop All" is a virtual collection (handled in the UI), never a stored row,
--     so it can never appear twice in navigation.
--   * Subcategory slugs are unique per parent category, so "wide-leg" may exist
--     under both Denim and Trousers without colliding.
--   * products.subcategory stores the subcategory SLUG (not a display name) so
--     the horizontal bar can filter directly.
--   * products.category is left untouched: it backs existing URLs, order history
--     snapshots and free-text search.
--   * Everything is idempotent and safe to re-run.

-- ---------------------------------------------------------------------------
-- 1. Correct the uniqueness rule for categories
-- ---------------------------------------------------------------------------
ALTER TABLE categories DROP CONSTRAINT IF EXISTS categories_gender_slug_key;
CREATE UNIQUE INDEX IF NOT EXISTS ux_categories_root_slug
  ON categories(gender, slug) WHERE parent_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_categories_child_slug
  ON categories(parent_id, slug) WHERE parent_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. Main categories
-- ---------------------------------------------------------------------------
INSERT INTO categories(id, gender, name, slug, parent_id, sort_order)
SELECT gen_random_uuid(), v.gender, v.name, v.slug, NULL, v.sort_order
FROM (VALUES
  ('MEN','Clothing','clothing',1),
  ('MEN','Denim','denim',2),
  ('MEN','Shirts','shirts',3),
  ('MEN','Trousers','trousers',4),
  ('MEN','Jackets & Coats','jackets-coats',5),
  ('MEN','Knitwear','knitwear',6),
  ('MEN','Hoodies & Sweatshirts','hoodies-sweatshirts',7),
  ('MEN','Suits & Blazers','suits-blazers',8),
  ('MEN','Shorts','shorts',9),
  ('MEN','Activewear','activewear',10),
  ('MEN','Footwear','footwear',11),
  ('MEN','Accessories','accessories',12),
  ('WOMEN','Clothing','clothing',1),
  ('WOMEN','Denim','denim',2),
  ('WOMEN','Tops & Shirts','tops-shirts',3),
  ('WOMEN','Dresses','dresses',4),
  ('WOMEN','Trousers','trousers',5),
  ('WOMEN','Skirts','skirts',6),
  ('WOMEN','Jackets & Coats','jackets-coats',7),
  ('WOMEN','Knitwear','knitwear',8),
  ('WOMEN','Co-ords','co-ords',9),
  ('WOMEN','Jumpsuits','jumpsuits',10),
  ('WOMEN','Activewear','activewear',11),
  ('WOMEN','Footwear','footwear',12),
  ('WOMEN','Bags & Accessories','bags-accessories',13)
) AS v(gender, name, slug, sort_order)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. Subcategories
-- ---------------------------------------------------------------------------
INSERT INTO categories(id, gender, name, slug, parent_id, sort_order)
SELECT gen_random_uuid(), v.gender, v.name, v.slug, p.id, v.sort_order
FROM (VALUES
  -- MEN / Denim
  ('MEN','denim','Straight','straight',1),
  ('MEN','denim','Slim','slim',2),
  ('MEN','denim','Relaxed','relaxed',3),
  ('MEN','denim','Wide Leg','wide-leg',4),
  ('MEN','denim','Skinny','skinny',5),
  ('MEN','denim','Bootcut','bootcut',6),
  ('MEN','denim','Flared','flared',7),
  ('MEN','denim','Jackets','jackets',8),
  -- MEN / Shirts
  ('MEN','shirts','Casual','casual',1),
  ('MEN','shirts','Formal','formal',2),
  ('MEN','shirts','Linen','linen',3),
  ('MEN','shirts','Oxford','oxford',4),
  ('MEN','shirts','Oversized','oversized',5),
  ('MEN','shirts','Printed','printed',6),
  ('MEN','shirts','Camp Collar','camp-collar',7),
  ('MEN','shirts','T-Shirts','t-shirts',8),
  -- MEN / Trousers
  ('MEN','trousers','Tailored','tailored',1),
  ('MEN','trousers','Chinos','chinos',2),
  ('MEN','trousers','Cargo','cargo',3),
  ('MEN','trousers','Pleated','pleated',4),
  ('MEN','trousers','Wide Leg','wide-leg',5),
  ('MEN','trousers','Relaxed','relaxed',6),
  -- MEN / Jackets & Coats
  ('MEN','jackets-coats','Field Jackets','field-jackets',1),
  ('MEN','jackets-coats','Bomber Jackets','bomber-jackets',2),
  ('MEN','jackets-coats','Parkas','parkas',3),
  ('MEN','jackets-coats','Coats','coats',4),
  -- MEN / Knitwear
  ('MEN','knitwear','Crew Knits','crew-knit',1),
  ('MEN','knitwear','Polo Knits','polo-knit',2),
  ('MEN','knitwear','Cardigans','cardigans',3),
  -- MEN / Hoodies & Sweatshirts
  ('MEN','hoodies-sweatshirts','Hoodies','hoodies',1),
  ('MEN','hoodies-sweatshirts','Sweatshirts','sweatshirts',2),
  -- MEN / Suits & Blazers
  ('MEN','suits-blazers','Suits','suits',1),
  ('MEN','suits-blazers','Blazers','blazers',2),
  ('MEN','suits-blazers','Dinner Suits','dinner-suits',3),
  -- MEN / Shorts
  ('MEN','shorts','Drawstring','drawstring',1),
  ('MEN','shorts','Knit','knit',2),
  ('MEN','shorts','Relaxed','relaxed',3),
  -- MEN / Activewear
  ('MEN','activewear','Track Jackets','track-jackets',1),
  ('MEN','activewear','Joggers','joggers',2),
  ('MEN','activewear','Performance Tops','performance-tops',3),
  -- MEN / Footwear
  ('MEN','footwear','Sneakers','sneakers',1),
  ('MEN','footwear','Leather Sneakers','leather-sneakers',2),
  ('MEN','footwear','Derby Shoes','derby-shoes',3),
  ('MEN','footwear','Formal Shoes','formal-shoes',4),
  ('MEN','footwear','Boots','boots',5),
  ('MEN','footwear','Sandals','sandals',6),
  -- MEN / Accessories
  ('MEN','accessories','Caps','caps',1),
  ('MEN','accessories','Belts','belts',2),
  ('MEN','accessories','Scarves','scarves',3),
  ('MEN','accessories','Bags','bags',4),
  -- WOMEN / Denim
  ('WOMEN','denim','Straight','straight',1),
  ('WOMEN','denim','Slim','slim',2),
  ('WOMEN','denim','Mom','mom',3),
  ('WOMEN','denim','Wide Leg','wide-leg',4),
  ('WOMEN','denim','Baggy','baggy',5),
  ('WOMEN','denim','Flared','flared',6),
  ('WOMEN','denim','Bootcut','bootcut',7),
  ('WOMEN','denim','Jackets','jackets',8),
  -- WOMEN / Tops & Shirts
  ('WOMEN','tops-shirts','Crop Tops','crop-tops',1),
  ('WOMEN','tops-shirts','Tank Tops','tank-tops',2),
  ('WOMEN','tops-shirts','Blouses','blouses',3),
  ('WOMEN','tops-shirts','Shirts','shirts',4),
  ('WOMEN','tops-shirts','Bodysuits','bodysuits',5),
  ('WOMEN','tops-shirts','Corset Tops','corset-tops',6),
  ('WOMEN','tops-shirts','Everyday Tops','everyday-tops',7),
  ('WOMEN','tops-shirts','Occasion Tops','occasion-tops',8),
  -- WOMEN / Dresses
  ('WOMEN','dresses','Mini','mini',1),
  ('WOMEN','dresses','Midi','midi',2),
  ('WOMEN','dresses','Maxi','maxi',3),
  ('WOMEN','dresses','Bodycon','bodycon',4),
  ('WOMEN','dresses','Shirt','shirt',5),
  ('WOMEN','dresses','Slip','slip',6),
  ('WOMEN','dresses','Occasion','occasion',7),
  -- WOMEN / Trousers
  ('WOMEN','trousers','Tailored','tailored',1),
  ('WOMEN','trousers','Wide Leg','wide-leg',2),
  ('WOMEN','trousers','Pleated','pleated',3),
  ('WOMEN','trousers','Relaxed','relaxed',4),
  ('WOMEN','trousers','Shorts','shorts',5),
  -- WOMEN / Skirts
  ('WOMEN','skirts','A-Line','a-line',1),
  ('WOMEN','skirts','Midi','midi',2),
  ('WOMEN','skirts','Mini','mini',3),
  ('WOMEN','skirts','Maxi','maxi',4),
  ('WOMEN','skirts','Pleated','pleated',5),
  -- WOMEN / Jackets & Coats
  ('WOMEN','jackets-coats','Blazers','blazers',1),
  ('WOMEN','jackets-coats','Utility Jackets','utility-jackets',2),
  ('WOMEN','jackets-coats','Cropped Jackets','cropped-jackets',3),
  ('WOMEN','jackets-coats','Coats','coats',4),
  -- WOMEN / Knitwear
  ('WOMEN','knitwear','Cardigans','cardigans',1),
  ('WOMEN','knitwear','Polo Knits','polo-knit',2),
  ('WOMEN','knitwear','Crew Knits','crew-knit',3),
  -- WOMEN / Co-ords
  ('WOMEN','co-ords','Tracksuits','tracksuits',1),
  ('WOMEN','co-ords','Sets','sets',2),
  -- WOMEN / Jumpsuits
  ('WOMEN','jumpsuits','Belted','belted',1),
  ('WOMEN','jumpsuits','Playsuits','playsuits',2),
  -- WOMEN / Activewear
  ('WOMEN','activewear','Leggings','leggings',1),
  ('WOMEN','activewear','Studio Jackets','studio-jackets',2),
  ('WOMEN','activewear','Performance Tops','performance-tops',3),
  -- WOMEN / Footwear
  ('WOMEN','footwear','Sneakers','sneakers',1),
  ('WOMEN','footwear','Ballet Flats','ballet-flats',2),
  ('WOMEN','footwear','Sandals','sandals',3),
  ('WOMEN','footwear','Heels','heels',4),
  -- WOMEN / Bags & Accessories
  ('WOMEN','bags-accessories','Totes','tote',1),
  ('WOMEN','bags-accessories','Shoulder Bags','shoulder-bags',2),
  ('WOMEN','bags-accessories','Crossbody Bags','crossbody',3),
  ('WOMEN','bags-accessories','Caps','caps',4),
  ('WOMEN','bags-accessories','Scarves','scarves',5),
  ('WOMEN','bags-accessories','Belts','belts',6)
) AS v(gender, parent_slug, name, slug, sort_order)
JOIN categories p ON p.slug = v.parent_slug AND p.gender = v.gender AND p.parent_id IS NULL
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. Classify every existing product onto gender + subcategory slug.
--    Keys are the legacy products.category values (kept intact for backwards
--    compatibility) combined with real style words from products.name.
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
-- 5. Link products to their main category collection(s).
--    A product can belong to more than one collection without being duplicated.
-- ---------------------------------------------------------------------------
INSERT INTO product_categories(product_id, category_id)
SELECT DISTINCT p.id, c.id
FROM products p
JOIN categories c
  ON c.gender = p.gender
 AND c.parent_id IS NULL
 AND (
      (c.gender = 'MEN' AND (
         (c.slug = 'denim'         AND p.category LIKE 'Men · Denim%')
      OR (c.slug = 'shirts'        AND p.category LIKE 'Men · Shirts%' OR p.category = 'Men · T-Shirts')
      OR (c.slug = 'trousers'      AND p.category = 'Men · Trousers')
      OR (c.slug = 'jackets-coats' AND p.category = 'Men · Jackets')
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
  AND p.gender IS NOT NULL
ON CONFLICT DO NOTHING;

-- "Clothing" is the broad collection: every apparel piece of that gender.
INSERT INTO product_categories(product_id, category_id)
SELECT DISTINCT p.id, c.id
FROM products p
JOIN categories c ON c.gender = p.gender AND c.slug = 'clothing' AND c.parent_id IS NULL
WHERE p.active = true
  AND p.gender IS NOT NULL
  AND p.category NOT IN (
    SELECT 'Men · Footwear' UNION ALL SELECT 'Men · Accessories' UNION ALL SELECT 'Men · Bags'
    UNION ALL SELECT 'Women · Footwear' UNION ALL SELECT 'Women · Accessories' UNION ALL SELECT 'Women · Bags'
  )
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 6. Filter support indexes
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_products_gender ON products(gender);
CREATE INDEX IF NOT EXISTS idx_products_gender_subcategory ON products(gender, subcategory);
CREATE INDEX IF NOT EXISTS idx_product_categories_product ON product_categories(product_id);

-- ---------------------------------------------------------------------------
-- 7. Footer / contact defaults
-- ---------------------------------------------------------------------------
INSERT INTO business_config(key, value) VALUES
  ('contact_email', 'support@atelierone.com'),
  ('contact_phone', '+91-9876543210'),
  ('business_address', 'Atelier One, Fashion District, Mumbai, India'),
  ('business_hours', 'Mon-Sat: 10:00 AM - 8:00 PM IST'),
  ('social_instagram', 'https://instagram.com/atelierone'),
  ('social_pinterest', 'https://pinterest.com/atelierone')
ON CONFLICT DO NOTHING;
