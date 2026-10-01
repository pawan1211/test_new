package com.fashion.catalog;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

/** Clearly marked, local-profile-only demo catalog; never touches orders or real products. */
@Configuration
@Profile("legacy-local")
public class LocalDemoCatalogSeeder {
  private static final String[][] LINES = {
    {"Men · T-shirts", "T-shirt", "Cotton", "1599", "clothing"},
    {"Men · Polos", "Polo", "Piqué", "2299", "clothing"},
    {"Men · Shirts", "Shirt", "Linen blend", "2899", "clothing"},
    {"Men · Denim", "Denim", "Washed cotton", "3299", "clothing"},
    {"Men · Trousers", "Trouser", "Tailored twill", "3499", "clothing"},
    {"Men · Chinos", "Chino", "Stretch cotton", "2999", "clothing"},
    {"Men · Jackets", "Jacket", "Structured cotton", "6499", "clothing"},
    {"Men · Hoodies", "Hoodie", "Brushed fleece", "3899", "clothing"},
    {"Men · Suits", "Suit", "Travel wool blend", "14999", "clothing"},
    {"Men · Ethnic wear", "Kurta", "Cotton slub", "3299", "clothing"},
    {"Men · Shorts", "Short", "Washed linen", "2199", "clothing"},
    {"Men · Joggers", "Jogger", "Soft jersey", "2799", "clothing"},
    {"Women · Dresses", "Dress", "Fluid crepe", "4999", "clothing"},
    {"Women · Jumpsuits", "Jumpsuit", "Textured linen", "5999", "clothing"},
    {"Women · Tops", "Top", "Silk touch satin", "2699", "clothing"},
    {"Women · Denim", "Denim", "Washed cotton", "3599", "clothing"},
    {"Women · Trousers", "Trouser", "Tailored twill", "3999", "clothing"},
    {"Women · Skirts", "Skirt", "Fluid viscose", "3299", "clothing"},
    {"Women · Co-ords", "Co-ord set", "Textured cotton", "6999", "clothing"},
    {"Women · Jackets", "Jacket", "Soft tailoring", "7499", "clothing"},
    {"Women · Sarees", "Saree", "Woven silk blend", "8999", "clothing"},
    {"Women · Kurtas", "Kurta", "Cotton voile", "3299", "clothing"},
    {"Women · Activewear", "Active set", "Performance jersey", "4499", "clothing"},
    {"Bags", "Tote", "Pebbled vegan leather", "6999", "bag"},
    {"Bags", "Shoulder bag", "Pebbled vegan leather", "7499", "bag"},
    {"Bags", "Crossbody bag", "Pebbled vegan leather", "5999", "bag"},
    {"Bags", "Mini bag", "Pebbled vegan leather", "4999", "bag"},
    {"Bags", "Clutch", "Satin and leather finish", "4599", "bag"},
    {"Bags", "Satchel", "Structured vegan leather", "7999", "bag"},
    {"Bags", "Travel duffel", "Recycled canvas", "8999", "bag"},
    {"Bags", "Backpack", "Recycled canvas", "6499", "bag"},
    {"Bags", "Laptop bag", "Pebbled vegan leather", "7999", "bag"},
    {"Bags", "Work bag", "Pebbled vegan leather", "8999", "bag"},
    {"Bags", "Wallet", "Pebbled vegan leather", "2499", "bag"},
    {"Shoes", "Sneaker", "Leather finish", "5499", "shoe"},
    {"Shoes", "Formal shoe", "Leather finish", "6999", "shoe"},
    {"Shoes", "Sandal", "Soft leather finish", "3999", "shoe"},
    {"Shoes", "Heel", "Soft leather finish", "5999", "shoe"},
    {"Shoes", "Flat", "Soft leather finish", "4499", "shoe"},
    {"Shoes", "Boot", "Leather finish", "7999", "shoe"},
    {"Men · Innerwear", "Innerwear", "Soft cotton", "1499", "clothing"},
    {"Men · Outerwear", "Outerwear layer", "Weather-ready twill", "8999", "clothing"},
    {"Men · Formal shoes", "Formal shoe", "Leather finish", "6999", "shoe"},
    {"Men · Sandals", "Sandal", "Soft leather finish", "3299", "shoe"},
    {"Women · Basics", "Essential top", "Soft cotton", "1799", "clothing"},
    {"Women · Shirts", "Shirt", "Cotton poplin", "2999", "clothing"},
    {"Women · Shorts", "Short", "Washed linen", "2499", "clothing"},
    {"Women · Sweaters", "Sweater", "Fine knit", "4999", "clothing"},
    {"Women · Occasion wear", "Occasion dress", "Fluid crepe", "8999", "clothing"},
    {"Women · Sleepwear", "Sleep set", "Soft cotton", "3299", "clothing"},
    {"Women · Heels", "Heel", "Soft leather finish", "5999", "shoe"},
    {"Women · Flats", "Flat", "Soft leather finish", "4499", "shoe"},
    {"Women · Boots", "Boot", "Leather finish", "7999", "shoe"},
    {"Accessories · Watches", "Watch", "Brushed steel", "7999", "accessory"},
    {"Accessories · Belts", "Belt", "Pebbled vegan leather", "2499", "accessory"},
    {"Accessories · Sunglasses", "Sunglasses", "Acetate frame", "3299", "accessory"},
    {"Accessories · Jewelry", "Jewelry piece", "Gold-tone finish", "2999", "accessory"},
    {"Accessories · Hats", "Hat", "Cotton twill", "1799", "accessory"},
    {"Accessories · Scarves", "Scarf", "Soft woven modal", "2199", "accessory"},
    {"Lifestyle · Travel", "Travel essential", "Recycled canvas", "3499", "accessory"},
    {"Lifestyle · Gifts", "Gift set", "Considered materials", "3999", "accessory"}
  };
  private static final String[] FORMS = {"Everyday", "Sculpted", "Relaxed", "Modern", "Essential", "Studio", "Weekend"};
  private static final String[] COLORS = {"Black", "Ivory", "Cocoa", "Olive", "Stone"};
  private static final String[] IMAGES = {
    "https://images.unsplash.com/photo-1521572163474-6864f9cf17ab?auto=format&fit=crop&w=900&q=80",
    "https://images.unsplash.com/photo-1596755094514-f87e34085b2c?auto=format&fit=crop&w=900&q=80",
    "https://images.unsplash.com/photo-1595777457583-95e059d581b8?auto=format&fit=crop&w=900&q=80",
    "https://images.unsplash.com/photo-1548036328-c9fa89d128fa?auto=format&fit=crop&w=900&q=80",
    "https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=900&q=80"
  };

  @Bean @Order(20)
  CommandLineRunner seedDemoCatalog(JdbcTemplate db) {
    return args -> {
      for (int i = 1; i <= 700; i++) {
        String[] line = LINES[(i - 1) % LINES.length];
        int variant = (i - 1) / LINES.length;
        String slug = String.format("demo-atelier-%03d", i);
        String name = String.format("Atelier Sample %03d — %s %s %02d", i, FORMS[variant % FORMS.length], line[1], variant + 1);
        String desc = "DEMO SAMPLE — Fabricated catalog record for local preview only. Not a commercial product, price, or availability promise. " + line[2] + " construction with considered proportions. Sample silhouette " + String.format("%03d", i) + ".";
        String image = IMAGES[line[4].equals("bag") ? 3 : line[4].equals("shoe") ? 4 : (i - 1) % 3];
        db.update("INSERT INTO products(id,slug,name,description,category,price,mrp,image_url,active) VALUES(?,?,?,?,?,?,?,?,true) ON CONFLICT(slug) DO NOTHING",
          UUID.nameUUIDFromBytes(slug.getBytes(java.nio.charset.StandardCharsets.UTF_8)), slug, name, desc, line[0], new BigDecimal(line[3]), new BigDecimal(line[3]).multiply(new BigDecimal("1.18")).setScale(0, java.math.RoundingMode.HALF_UP), image);
        UUID id = db.queryForObject("SELECT id FROM products WHERE slug=?", UUID.class, slug);
        String[] sizes = line[4].equals("bag") || line[4].equals("accessory") ? new String[]{"One Size"} : line[4].equals("shoe") ? new String[]{"36", "38", "40", "42", "44"} : new String[]{"XS", "S", "M", "L", "XL"};
        for (String size : sizes) for (String color : COLORS) {
          String sku = String.format("AO-DEMO-%03d-%s-%s", i, size.replace(" ", "").toUpperCase(), color.toUpperCase());
          db.update("INSERT INTO product_variants(id,product_id,sku,size,color,stock_quantity,active) VALUES(?,?,?,?,?,?,true) ON CONFLICT DO NOTHING",
            UUID.nameUUIDFromBytes(sku.getBytes(java.nio.charset.StandardCharsets.UTF_8)), id, sku, size, color, 4 + (i % 13));
        }
      }
    };
  }
}
