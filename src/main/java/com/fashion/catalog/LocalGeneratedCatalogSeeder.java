package com.fashion.catalog;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@Profile("local")
public class LocalGeneratedCatalogSeeder {
  @Bean
  CommandLineRunner seedAtelierCatalog(JdbcTemplate db, PlatformTransactionManager transactions) {
    return args -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
      // Keep prior product records for order history, but replace the customer-facing catalog.
      db.update("UPDATE product_variants SET active=false WHERE active=true");
      int archived = db.update("UPDATE products SET active=false WHERE active=true");
      int products = db.update("""
          WITH genders(gender_key, label) AS (VALUES ('men','Men'),('women','Women')),
          styles(gender_key,n,name,category,base_price,image_id,kind) AS (VALUES
            ('men',1,'Straight Leg Jean','Denim · Jeans',3499,'1542272604-787c3835535d','apparel'),
            ('men',2,'Relaxed Denim Jean','Denim · Jeans',3699,'1542272604-787c3835535d','apparel'),
            ('men',3,'Denim Trucker Jacket','Denim · Jackets',5499,'1551232864-3f0890e580d9','apparel'),
            ('men',4,'Oxford Button-Down Shirt','Shirts · Oxford',2899,'1596755094514-f87e34085b2c','apparel'),
            ('men',5,'Relaxed Camp-Collar Shirt','Shirts · Camp Collar',2699,'1521572163474-6864f9cf17ab','apparel'),
            ('men',6,'Everyday Overshirt','Shirts · Overshirts',3299,'1529139574466-a303027c1d8b','apparel'),
            ('men',7,'Textured Knit Polo','Knitwear',3299,'1485230895905-ec40ba36b9bc','apparel'),
            ('men',8,'Fine-Gauge Crew Knit','Knitwear',3799,'1485230895905-ec40ba36b9bc','apparel'),
            ('men',9,'Zip-Through Cardigan','Knitwear',4299,'1485230895905-ec40ba36b9bc','apparel'),
            ('men',10,'Pleated Tailored Trouser','Trousers',3899,'1490481651871-ab68de25d43d','apparel'),
            ('men',11,'Relaxed Chino','Trousers',3299,'1490481651871-ab68de25d43d','apparel'),
            ('men',12,'Utility Cargo Trouser','Trousers',3899,'1483985988355-763728e1935b','apparel'),
            ('men',13,'Drawstring Weekend Short','Shorts',2399,'1521572163474-6864f9cf17ab','apparel'),
            ('men',14,'Lightweight Bomber Jacket','Jackets',5999,'1551232864-3f0890e580d9','apparel'),
            ('men',15,'Unstructured Blazer','Jackets',7499,'1539107386315-e1a2ed48a620','apparel'),
            ('men',16,'Essential Crew T-Shirt','T-Shirts',1499,'1521572163474-6864f9cf17ab','apparel'),
            ('men',17,'Heavyweight Boxy T-Shirt','T-Shirts',1799,'1521572163474-6864f9cf17ab','apparel'),
            ('men',18,'Track Zip Jacket','Activewear',3999,'1539107386315-e1a2ed48a620','apparel'),
            ('men',19,'Tapered Training Jogger','Activewear',2999,'1490481651871-ab68de25d43d','apparel'),
            ('men',20,'Minimal Leather Sneaker','Footwear',4999,'1542291026-7eec264c27ff','shoe'),
            ('men',21,'Canvas Low-Top Sneaker','Footwear',3499,'1525966222134-fcfa99b8ae77','shoe'),
            ('men',22,'Lace-Up Derby Shoe','Footwear',6499,'1614252369475-531eba835eb1','shoe'),
            ('men',23,'Everyday Crossbody Bag','Bags',3499,'1548036328-c9fa89d128fa','bag'),
            ('men',24,'Structured Weekender','Bags',5999,'1553062407-98eeb64c6a62','bag'),
            ('men',25,'Reversible Leather Belt','Accessories',1999,'1624222247344-550fb8bc1e48','accessory'),
            ('men',26,'Cotton Twill Cap','Accessories',1299,'1588850561407-ed78c282e89b','accessory'),
            ('men',27,'Soft Woven Scarf','Accessories',1899,'1601924994987-69e26d50dc26','accessory'),
            ('men',28,'Relaxed Knit Short','Shorts',2299,'1521572163474-6864f9cf17ab','apparel'),
            ('men',29,'Field Overshirt Jacket','Jackets',5799,'1551232864-3f0890e580d9','apparel'),
            ('men',30,'Linen Blend Short-Sleeve Shirt','Shirts · Short Sleeve',2799,'1521572163474-6864f9cf17ab','apparel'),
            ('women',1,'Straight Leg Jean','Denim · Jeans',3499,'1542272604-787c3835535d','apparel'),
            ('women',2,'Wide Leg Denim Jean','Denim · Jeans',3799,'1542272604-787c3835535d','apparel'),
            ('women',3,'Cropped Denim Jacket','Denim · Jackets',5299,'1551232864-3f0890e580d9','apparel'),
            ('women',4,'Sculpted Poplin Blouse','Tops · Blouses',2899,'1539107386315-e1a2ed48a620','apparel'),
            ('women',5,'Draped Satin Top','Tops · Occasion',3299,'1595777457583-95e059d581b8','apparel'),
            ('women',6,'Ribbed Everyday Top','Tops',1899,'1521572163474-6864f9cf17ab','apparel'),
            ('women',7,'Fine-Gauge Cardigan','Knitwear',3799,'1485230895905-ec40ba36b9bc','apparel'),
            ('women',8,'Sculpted Knit Polo','Knitwear',2999,'1485230895905-ec40ba36b9bc','apparel'),
            ('women',9,'Fluid Wide-Leg Trouser','Trousers',3699,'1490481651871-ab68de25d43d','apparel'),
            ('women',10,'Pleated Tailored Trouser','Trousers',3899,'1490481651871-ab68de25d43d','apparel'),
            ('women',11,'Cotton Midi Dress','Dresses',4499,'1539107386315-e1a2ed48a620','apparel'),
            ('women',12,'Draped Occasion Dress','Dresses',5999,'1595777457583-95e059d581b8','apparel'),
            ('women',13,'Fluid Slip Dress','Dresses',5299,'1595777457583-95e059d581b8','apparel'),
            ('women',14,'Pleated Midi Skirt','Skirts',3299,'1595777457583-95e059d581b8','apparel'),
            ('women',15,'A-Line Everyday Skirt','Skirts',2999,'1595777457583-95e059d581b8','apparel'),
            ('women',16,'Sculpted Utility Jacket','Jackets',5799,'1551232864-3f0890e580d9','apparel'),
            ('women',17,'Cropped Relaxed Blazer','Jackets',6499,'1539107386315-e1a2ed48a620','apparel'),
            ('women',18,'Fluid Belted Jumpsuit','Jumpsuits',4999,'1539107386315-e1a2ed48a620','apparel'),
            ('women',19,'Low-Profile Leather Sneaker','Footwear',4999,'1542291026-7eec264c27ff','shoe'),
            ('women',20,'Minimal Ballet Flat','Footwear',3999,'1543163521-1bf539c55dd2','shoe'),
            ('women',21,'Sculpted Everyday Sandal','Footwear',3499,'1543163521-1bf539c55dd2','shoe'),
            ('women',22,'Structured Shoulder Bag','Bags',5499,'1548036328-c9fa89d128fa','bag'),
            ('women',23,'Soft Everyday Tote','Bags',3999,'1553062407-98eeb64c6a62','bag'),
            ('women',24,'Performance Legging','Activewear',2799,'1490481651871-ab68de25d43d','apparel'),
            ('women',25,'Studio Zip Jacket','Activewear',4299,'1539107386315-e1a2ed48a620','apparel'),
            ('women',26,'Fine Knit Scarf','Accessories',1899,'1601924994987-69e26d50dc26','accessory'),
            ('women',27,'Soft Leather Belt','Accessories',1999,'1624222247344-550fb8bc1e48','accessory'),
            ('women',28,'Cotton Baseball Cap','Accessories',1299,'1588850561407-ed78c282e89b','accessory'),
            ('women',29,'Linen Blend Shirt Dress','Dresses · Shirt Dresses',4799,'1539107386315-e1a2ed48a620','apparel'),
            ('women',30,'Relaxed Weekend Short','Shorts',2399,'1521572163474-6864f9cf17ab','apparel')
          ),
          finishes(kind,n,finish) AS (VALUES
            ('apparel',1,'Airy Cotton'),('apparel',2,'Washed Cotton'),('apparel',3,'Textured Linen'),
            ('apparel',4,'Soft Poplin'),('apparel',5,'Organic Jersey'),('apparel',6,'Compact Rib'),
            ('apparel',7,'Fluid Crepe'),('apparel',8,'Tencel Blend'),('apparel',9,'Brushed Twill'),('apparel',10,'Seersucker'),
            ('shoe',1,'Soft Leather'),('shoe',2,'Suede'),('shoe',3,'Canvas'),('shoe',4,'Mesh'),
            ('shoe',5,'Textured Leather'),('shoe',6,'Recycled Knit'),('shoe',7,'Nubuck'),('shoe',8,'Smooth Vegan Leather'),
            ('shoe',9,'Woven Textile'),('shoe',10,'Pebbled Leather'),
            ('bag',1,'Pebbled Leather'),('bag',2,'Washed Canvas'),('bag',3,'Soft Vegan Leather'),('bag',4,'Textured Suede'),
            ('bag',5,'Woven Raffia'),('bag',6,'Quilted Nylon'),('bag',7,'Recycled Twill'),('bag',8,'Grained Leather'),
            ('bag',9,'Cotton Canvas'),('bag',10,'Satin'),
            ('accessory',1,'Pebbled Leather'),('accessory',2,'Soft Cotton'),('accessory',3,'Woven Linen'),
            ('accessory',4,'Brushed Suede'),('accessory',5,'Recycled Canvas'),('accessory',6,'Smooth Leather'),
            ('accessory',7,'Textured Cotton'),('accessory',8,'Wool Blend'),('accessory',9,'Twill'),('accessory',10,'Soft Satin')
          ),
          candidates AS (
            SELECT 'atelier-curated-'||s.gender_key||'-'||LPAD(s.n::text,2,'0')||'-'||LPAD(f.n::text,2,'0') slug,
              'Atelier '||g.label||' '||f.finish||' '||s.name product_name,
              'A considered Atelier One '||LOWER(f.finish)||' '||LOWER(s.name)||' with a modern silhouette, thoughtful proportions and refined finishing.' description,
              g.label||' · '||s.category category,
              s.base_price+(f.n*75) price,
              'https://images.unsplash.com/photo-'||s.image_id||'?auto=format&fit=crop&w=1400&q=85' image_url,
              s.kind
            FROM styles s JOIN genders g ON g.gender_key=s.gender_key JOIN finishes f ON f.kind=s.kind
          )
          INSERT INTO products(id,slug,name,description,category,price,mrp,image_url,active)
          SELECT gen_random_uuid(),slug,product_name,description,category,price,ROUND(price/0.85/100.0)*100,image_url,true FROM candidates
          ON CONFLICT(slug) DO UPDATE SET name=EXCLUDED.name,description=EXCLUDED.description,category=EXCLUDED.category,
          price=EXCLUDED.price,mrp=EXCLUDED.mrp,image_url=EXCLUDED.image_url,active=true
          """);

      db.update("""
          INSERT INTO product_variants(id,product_id,sku,size,color,color_hex,stock_quantity,image_url,active)
          SELECT gen_random_uuid(),p.id,UPPER(REPLACE(p.slug,'-','_'))||'_'||LOWER(c.color)||'_'||s.size,
                 s.size,c.color,c.hex,18,p.image_url,true
          FROM products p
          CROSS JOIN (VALUES ('Black','#171715'),('Ivory','#eee8dd'),('Cocoa','#755746'),('Olive','#687052'),('Stone','#b4aa99')) c(color,hex)
          CROSS JOIN LATERAL (SELECT unnest(CASE
            WHEN p.category LIKE '% · Bags' THEN ARRAY['OS']::text[]
            WHEN p.category LIKE '% · Footwear' THEN ARRAY['6','7','8','9','10']::text[]
            ELSE ARRAY['XS','S','M','L','XL']::text[] END) size) s
          WHERE p.slug LIKE 'atelier-curated-%'
          ON CONFLICT(product_id,size,color) DO UPDATE SET active=true,stock_quantity=GREATEST(product_variants.stock_quantity,18)
          """);

      // Classify the freshly published products onto the canonical taxonomy.
      // Runs here rather than only in a Flyway migration because this seeder
      // creates products *after* migrations have already completed, so on a fresh
      // database a migration-only mapping would leave the navigation empty.
      db.execute((ConnectionCallback<Void>) connection -> {
        ScriptUtils.executeSqlScript(connection,
            new EncodedResource(new ClassPathResource("db/taxonomy/apply-product-taxonomy.sql"), "UTF-8"));
        return null;
      });

      System.out.printf("Local catalog replaced: archived %d old products; published %d curated products.%n", archived, products);
    });
  }
}
