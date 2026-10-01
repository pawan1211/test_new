package com.fashion.catalog;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

@Configuration
@Profile("legacy-local")
public class LocalCatalogSeeder {
  @Bean
  CommandLineRunner seedCatalog(ProductRepository repo, JdbcTemplate db) {
    return args -> {
      seedProduct(repo, "relaxed-ivory-tee", "Relaxed Cotton Tee",
          "A heavyweight cotton essential with a relaxed silhouette and considered finishing.",
          "Men", "1299", "1799",
          "https://images.unsplash.com/photo-1521572163474-6864f9cf17ab?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "everyday-linen-shirt", "Everyday Linen Shirt",
          "An easy linen-blend shirt designed for warm days and layered evenings.",
          "Men", "2499", "3299",
          "https://images.unsplash.com/photo-1596755094514-f87e34085b2c?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "sculpted-midi-dress", "Sculpted Midi Dress",
          "A clean, versatile silhouette with a soft drape and understated details.",
          "Women", "3599", "4299",
          "https://images.unsplash.com/photo-1595777457583-95e059d581b8?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "minimal-crossbody", "Minimal Crossbody Bag",
          "A compact everyday carryall with a clean profile and adjustable strap.",
          "Accessories", "2199", null,
          "https://images.unsplash.com/photo-1548036328-c9fa89d128fa?auto=format&fit=crop&w=1200&q=85");

      seedProduct(repo, "linen-easy-trouser", "Linen Easy Trouser", "A breathable relaxed trouser with a clean tailored line.", "Men", "2899", "3599", "https://images.unsplash.com/photo-1521572163474-6864f9cf17ab?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "classic-oxford-shirt", "Classic Oxford Shirt", "A crisp everyday shirt with a softly structured collar.", "Men", "2299", "2999", "https://images.unsplash.com/photo-1596755094514-f87e34085b2c?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "boxy-pocket-shirt", "Boxy Pocket Shirt", "A modern boxy shirt with considered utility details.", "Men", "2199", "2799", "https://images.unsplash.com/photo-1595777457583-95e059d581b8?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "tailored-camp-collar", "Tailored Camp Collar Shirt", "A warm-weather staple with an easy drape.", "Men", "1999", "2599", "https://images.unsplash.com/photo-1548036328-c9fa89d128fa?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "straight-fit-denim", "Straight Fit Denim", "A versatile straight-leg denim with a timeless wash.", "Men", "2999", "3799", "https://images.unsplash.com/photo-1529139574466-a303027c1d8b?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "relaxed-cargo-pant", "Relaxed Cargo Pant", "A contemporary cargo with clean pockets and a relaxed fit.", "Men", "3199", "3999", "https://images.unsplash.com/photo-1539107386315-e1a2ed48a620?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "ribbed-knit-polo", "Ribbed Knit Polo", "A refined knit polo for elevated everyday dressing.", "Men", "2499", "3199", "https://images.unsplash.com/photo-1483985988355-763728e1935b?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "structured-overshirt", "Structured Overshirt", "A lightweight layering piece with a tailored finish.", "Men", "3499", "4299", "https://images.unsplash.com/photo-1485230895905-ec40ba36b9bc?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "weekend-crew-sweatshirt", "Weekend Crew Sweatshirt", "A soft brushed crewneck designed for off-duty days.", "Men", "2699", "3399", "https://images.unsplash.com/photo-1490481651871-ab68de25d43d?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "minimal-leather-belt", "Minimal Leather Belt", "A clean leather belt with understated hardware.", "Accessories", "1499", "1999", "https://images.unsplash.com/photo-1551232864-3f0890e580d9?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "wide-leg-linen-pant", "Wide Leg Linen Pant", "An airy wide-leg silhouette with a fluid finish.", "Women", "2899", "3599", "https://images.unsplash.com/photo-1521572163474-6864f9cf17ab?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "everyday-poplin-shirt", "Everyday Poplin Shirt", "A crisp poplin shirt designed for effortless layering.", "Women", "2399", "2999", "https://images.unsplash.com/photo-1596755094514-f87e34085b2c?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "draped-satin-top", "Draped Satin Top", "A softly draped top with a subtle evening sheen.", "Women", "2199", "2799", "https://images.unsplash.com/photo-1595777457583-95e059d581b8?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "ribbed-tank-top", "Ribbed Tank Top", "A versatile ribbed essential with a flattering shape.", "Women", "999", "1499", "https://images.unsplash.com/photo-1548036328-c9fa89d128fa?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "tailored-bermuda-short", "Tailored Bermuda Short", "A polished warm-weather short with a clean front.", "Women", "2299", "2899", "https://images.unsplash.com/photo-1529139574466-a303027c1d8b?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "pleated-midi-skirt", "Pleated Midi Skirt", "A movement-led midi skirt with soft pleating.", "Women", "2799", "3499", "https://images.unsplash.com/photo-1539107386315-e1a2ed48a620?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "linen-wrap-dress", "Linen Wrap Dress", "A breathable wrap dress with an adjustable waist.", "Women", "3899", "4699", "https://images.unsplash.com/photo-1483985988355-763728e1935b?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "everyday-knit-cardigan", "Everyday Knit Cardigan", "A lightweight cardigan for easy year-round layering.", "Women", "3299", "3999", "https://images.unsplash.com/photo-1485230895905-ec40ba36b9bc?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "sculpted-square-neck-top", "Sculpted Square Neck Top", "A minimal top with a modern neckline and clean finish.", "Women", "1799", "2299", "https://images.unsplash.com/photo-1490481651871-ab68de25d43d?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "relaxed-blazer", "Relaxed Tailored Blazer", "An easy blazer with a soft shoulder and fluid shape.", "Women", "4999", "5999", "https://images.unsplash.com/photo-1551232864-3f0890e580d9?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "cropped-denim-jacket", "Cropped Denim Jacket", "A modern denim layer with a cropped proportion.", "Women", "3999", "4999", "https://images.unsplash.com/photo-1521572163474-6864f9cf17ab?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "soft-pleat-trouser", "Soft Pleat Trouser", "A tailored trouser with relaxed movement.", "Women", "2999", "3799", "https://images.unsplash.com/photo-1596755094514-f87e34085b2c?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "day-to-night-slip-dress", "Day To Night Slip Dress", "A fluid slip dress made for day-to-evening styling.", "Women", "4299", "5299", "https://images.unsplash.com/photo-1595777457583-95e059d581b8?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "cotton-poplin-midi-dress", "Cotton Poplin Midi Dress", "A crisp cotton dress with a considered silhouette.", "Women", "3599", "4499", "https://images.unsplash.com/photo-1548036328-c9fa89d128fa?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "crochet-knit-polo", "Crochet Knit Polo", "A textured knit polo with a relaxed resort feel.", "Women", "2799", "3499", "https://images.unsplash.com/photo-1529139574466-a303027c1d8b?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "fine-knit-sleeveless-top", "Fine Knit Sleeveless Top", "A refined fine-gauge knit for versatile styling.", "Women", "1899", "2399", "https://images.unsplash.com/photo-1539107386315-e1a2ed48a620?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "everyday-shoulder-bag", "Everyday Shoulder Bag", "A compact shoulder bag with a sculptural profile.", "Accessories", "2999", "3799", "https://images.unsplash.com/photo-1483985988355-763728e1935b?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "woven-market-tote", "Woven Market Tote", "A roomy woven tote for everyday essentials.", "Accessories", "1799", "2299", "https://images.unsplash.com/photo-1485230895905-ec40ba36b9bc?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "mini-crossbody-bag", "Mini Crossbody Bag", "A compact crossbody for hands-free everyday wear.", "Accessories", "2499", "3199", "https://images.unsplash.com/photo-1490481651871-ab68de25d43d?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "soft-structured-cap", "Soft Structured Cap", "A minimal cap with an adjustable back strap.", "Accessories", "999", "1299", "https://images.unsplash.com/photo-1551232864-3f0890e580d9?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "classic-silk-scarf", "Classic Silk-Feel Scarf", "A versatile printed scarf for effortless styling.", "Accessories", "1299", "1699", "https://images.unsplash.com/photo-1521572163474-6864f9cf17ab?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "everyday-slim-wallet", "Everyday Slim Wallet", "A streamlined wallet with practical card storage.", "Accessories", "1599", "1999", "https://images.unsplash.com/photo-1596755094514-f87e34085b2c?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "minimal-hoop-earrings", "Minimal Hoop Earrings", "Sculptural everyday hoops with a polished finish.", "Accessories", "1199", "1599", "https://images.unsplash.com/photo-1595777457583-95e059d581b8?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "resort-slide-sandal", "Resort Slide Sandal", "A clean, comfortable slide for warm-weather dressing.", "Accessories", "1999", "2599", "https://images.unsplash.com/photo-1548036328-c9fa89d128fa?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "city-low-top-sneaker", "City Low Top Sneaker", "A versatile low-top sneaker with a clean profile.", "Accessories", "3999", "4999", "https://images.unsplash.com/photo-1529139574466-a303027c1d8b?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "minimal-bucket-hat", "Minimal Bucket Hat", "A modern bucket hat with an easy structured brim.", "Accessories", "1299", "1699", "https://images.unsplash.com/photo-1539107386315-e1a2ed48a620?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "lightweight-trench", "Lightweight Trench", "A transitional trench with a relaxed tailored cut.", "Outerwear", "5999", "7299", "https://images.unsplash.com/photo-1483985988355-763728e1935b?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "cropped-bomber-jacket", "Cropped Bomber Jacket", "A contemporary bomber with a clean, compact shape.", "Outerwear", "5499", "6799", "https://images.unsplash.com/photo-1485230895905-ec40ba36b9bc?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "quilted-liner-jacket", "Quilted Liner Jacket", "A lightweight quilted layer for changing seasons.", "Outerwear", "4299", "5299", "https://images.unsplash.com/photo-1490481651871-ab68de25d43d?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "soft-wool-blend-coat", "Soft Wool Blend Coat", "A refined longline coat with a minimal finish.", "Outerwear", "8999", "10999", "https://images.unsplash.com/photo-1551232864-3f0890e580d9?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "utility-field-jacket", "Utility Field Jacket", "A functional field jacket with modern proportions.", "Outerwear", "6499", "7999", "https://images.unsplash.com/photo-1521572163474-6864f9cf17ab?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "relaxed-knit-hoodie", "Relaxed Knit Hoodie", "A premium-feel hoodie with a clean silhouette.", "Men", "3299", "3999", "https://images.unsplash.com/photo-1596755094514-f87e34085b2c?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "textured-linen-blend-tee", "Textured Linen Blend Tee", "A breathable tee with a subtle textured surface.", "Men", "1599", "1999", "https://images.unsplash.com/photo-1595777457583-95e059d581b8?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "double-pleat-short", "Double Pleat Short", "A tailored short with a relaxed leg and refined pleats.", "Men", "2299", "2899", "https://images.unsplash.com/photo-1548036328-c9fa89d128fa?auto=format&fit=crop&w=1200&q=85");
      seedProduct(repo, "lightweight-knit-pullover", "Lightweight Knit Pullover", "A versatile lightweight knit for smart layering.", "Men", "2999", "3699", "https://images.unsplash.com/photo-1529139574466-a303027c1d8b?auto=format&fit=crop&w=1200&q=85");

      seedProduct(repo, "washed-denim-overshirt", "Washed Denim Overshirt", "A softly structured denim layer with a relaxed fit.", "Men", "3799", "4699", "https://images.unsplash.com/photo-1529139574466-a303027c1d8b?auto=format&fit=crop&w=1200&q=85");

      // Create local test variants for all 50 seeded catalog items.
      String[] seededSlugs = new String[]{"relaxed-ivory-tee", "everyday-linen-shirt", "sculpted-midi-dress", "minimal-crossbody",
          "linen-easy-trouser",
          "classic-oxford-shirt",
          "boxy-pocket-shirt",
          "tailored-camp-collar",
          "straight-fit-denim",
          "relaxed-cargo-pant",
          "ribbed-knit-polo",
          "structured-overshirt",
          "weekend-crew-sweatshirt",
          "minimal-leather-belt",
          "wide-leg-linen-pant",
          "everyday-poplin-shirt",
          "draped-satin-top",
          "ribbed-tank-top",
          "tailored-bermuda-short",
          "pleated-midi-skirt",
          "linen-wrap-dress",
          "everyday-knit-cardigan",
          "sculpted-square-neck-top",
          "relaxed-blazer",
          "cropped-denim-jacket",
          "soft-pleat-trouser",
          "day-to-night-slip-dress",
          "cotton-poplin-midi-dress",
          "crochet-knit-polo",
          "fine-knit-sleeveless-top",
          "everyday-shoulder-bag",
          "woven-market-tote",
          "mini-crossbody-bag",
          "soft-structured-cap",
          "classic-silk-scarf",
          "everyday-slim-wallet",
          "minimal-hoop-earrings",
          "resort-slide-sandal",
          "city-low-top-sneaker",
          "minimal-bucket-hat",
          "lightweight-trench",
          "cropped-bomber-jacket",
          "quilted-liner-jacket",
          "soft-wool-blend-coat",
          "utility-field-jacket",
          "relaxed-knit-hoodie",
          "textured-linen-blend-tee",
          "double-pleat-short",
          "lightweight-knit-pullover",
          "washed-denim-overshirt"
      };
      for (String slug : seededSlugs) {
        UUID productId = db.queryForObject("SELECT id FROM products WHERE slug=?", UUID.class, slug);
        String category = db.queryForObject("SELECT category FROM products WHERE id=?", String.class, productId);
        if ("Accessories".equals(category)) {
          db.update("INSERT INTO product_variants(product_id,sku,size,color,stock_quantity,active) VALUES(?,?,?,?,25,true) ON CONFLICT DO NOTHING", productId, slug.toUpperCase().replace('-', '_') + "_ONE_SIZE", "One Size", "Mixed");
        } else {
          for (String size : new String[]{"XS", "S", "M", "L", "XL"}) {
            String sku = slug.toUpperCase().replace('-', '_') + "_" + size;
            db.update("INSERT INTO product_variants(product_id,sku,size,color,stock_quantity,active) VALUES(?,?,?,?,25,true) ON CONFLICT DO NOTHING", productId, sku, size, "Assorted");
          }
        }
      }
    };
  }

  private void seedProduct(ProductRepository repo, String slug, String name, String description,
                           String category, String price, String mrp, String image) {
    if (repo.findBySlugAndActiveTrue(slug).isPresent()) return;
    repo.save(new Product(UUID.randomUUID(), slug, name, description, category,
        new BigDecimal(price), mrp == null ? null : new BigDecimal(mrp), image, true));
  }
}
