-- V19: Repair the product_categories links written by V18.
--
-- V18 grouped the MEN "shirts" clause as
--     c.slug = 'shirts' AND p.category LIKE 'Men · Shirts%' OR p.category = 'Men · T-Shirts'
-- where AND binds tighter than OR, so the second branch was true for *every*
-- main category. That attached all 20 men's T-Shirts to all twelve MEN
-- collections, inflating each count by 20.
--
-- This migration removes exactly those spurious links and re-derives the MEN
-- associations with correct grouping. It is a no-op on a database that already
-- has correct links.

DELETE FROM product_categories pc
USING products p, categories c
WHERE pc.product_id = p.id
  AND pc.category_id = c.id
  AND c.parent_id IS NULL
  AND c.gender = 'MEN'
  AND p.category = 'Men · T-Shirts'
  AND c.slug <> 'shirts';

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
