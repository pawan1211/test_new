package com.fashion.commerce;

import org.springframework.jdbc.core.JdbcTemplate;
import com.fashion.security.CustomerAuthController;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins="${app.cors-origin:http://localhost:3000}")
public class CommerceController {
  private final JdbcTemplate db; private final CustomerAuthController auth; private final InventoryService inventoryService;
  public CommerceController(JdbcTemplate db, CustomerAuthController auth, InventoryService inventoryService){this.db=db;this.auth=auth;this.inventoryService=inventoryService;}

  private UUID guestSession(String raw){
    if(raw==null||raw.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A valid X-Storefront-Session UUID is required");
    try{return UUID.fromString(raw);}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A valid X-Storefront-Session UUID is required");}
  }

  private void ensureSession(UUID id){db.update("INSERT INTO storefront_sessions(id) VALUES (?) ON CONFLICT(id) DO UPDATE SET updated_at=now()",id);}

  /**
   * Resolves which bag a request operates on.
   *
   * <p>For a signed-in customer the bag is a row owned by their <em>account</em>,
   * found through the partial unique index on {@code customer_id}. Because a bag
   * session is keyed by account and never by browser, two accounts signing in from
   * the same machine get different bags, and one account gets the same bag on every
   * device.
   *
   * <p>An earlier version of this reused the browser's session id as the account's
   * bag row, stamping {@code customer_id} onto it on first sign-in. That was wrong:
   * whoever signed in first from a browser claimed the row for good, so a second
   * account on the same machine resolved to the first account's bag. It is also why
   * the browser id is still required for guests — it is the only thing that
   * identifies them — but it is never allowed to decide a signed-in customer's bag.
   *
   * <p>An invalid token is reported rather than falling back to the browser's bag:
   * silently serving a guest bag to an expired session is how one shopper ends up
   * looking at another's cart.
   */
  private UUID resolveSession(String rawSession, String authorization){
    // A guest's bag is the browser's. Also validates the header so a malformed id
    // is reported consistently whether or not a token is present.
    UUID browserSession=guestSession(rawSession);

    if(authorization==null||authorization.isBlank()){
      ensureSession(browserSession);
      return browserSession;
    }

    UUID customerId=auth.subject(authorization);

    List<Map<String,Object>> owned=db.queryForList(
      "SELECT id FROM storefront_sessions WHERE customer_id=? LIMIT 1",customerId);
    if(!owned.isEmpty())return (UUID)owned.get(0).get("id");

    // First sign-in from this account: create a dedicated bag row. The insert is
    // conditional so two tabs racing here cannot create two rows; the loser re-reads
    // and gets the winner's row.
    db.update("INSERT INTO storefront_sessions(id,customer_id) " +
      "SELECT ?,? WHERE NOT EXISTS (SELECT 1 FROM storefront_sessions WHERE customer_id=?)",
      UUID.randomUUID(),customerId,customerId);

    owned=db.queryForList("SELECT id FROM storefront_sessions WHERE customer_id=? LIMIT 1",customerId);
    if(!owned.isEmpty())return (UUID)owned.get(0).get("id");

    // Could not create a row (for example the account row vanished mid-request).
    // Refusing is right: returning the browser session here would serve one
    // account's browser bag to another account.
    throw new ResponseStatusException(HttpStatus.CONFLICT,
      "We could not open your bag just now. Please try again.");
  }

  @GetMapping("/commerce/variants/resolve")
  public Map<String, Object> resolveVariant(@RequestParam UUID productId, @RequestParam String size) {
    List<Map<String, Object>> rows=db.queryForList(
      "SELECT v.id AS \"variantId\", v.product_id AS \"productId\", v.size, v.color, v.stock_quantity AS \"stockQuantity\" " +
      "FROM product_variants v JOIN products p ON p.id = v.product_id " +
      "WHERE v.product_id = ? AND LOWER(v.size) = LOWER(?) AND v.active = true AND p.active = true " +
      "ORDER BY v.stock_quantity DESC LIMIT 1", productId, size);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No active variant for selected product and size");
    Map<String, Object> variant = new LinkedHashMap<>(rows.get(0));
    int stock = ((Number) variant.get("stockQuantity")).intValue();
    variant.put("available", stock > 0);
    variant.put("message", stock > 0 ? "In stock" : "Out of stock");
    return variant;
  }

  @GetMapping("/commerce/cart") public Map<String,Object> cart(@RequestHeader("X-Storefront-Session") String raw,@RequestHeader(value="Authorization",required=false) String authorization){return cartData(resolveSession(raw,authorization));}
  private Map<String,Object> cartData(UUID s){
    List<Map<String,Object>> items=db.queryForList(
      "SELECT ci.variant_id AS \"variantId\", ci.quantity AS quantity, p.id AS \"productId\", p.slug, p.name, p.price, p.image_url AS \"imageUrl\", v.sku, v.size, v.color, v.stock_quantity AS stock " +
      "FROM storefront_cart_items ci JOIN product_variants v ON v.id=ci.variant_id JOIN products p ON p.id=v.product_id " +
      "WHERE ci.session_id=? AND p.active AND v.active ORDER BY ci.created_at", s);
    BigDecimal subtotal=items.stream().map(x->((BigDecimal)x.get("price")).multiply(BigDecimal.valueOf(((Number)x.get("quantity")).intValue()))).reduce(BigDecimal.ZERO,BigDecimal::add);
    return Map.of("items",items,"subtotal",subtotal,"currency","INR");
  }

  @PostMapping("/commerce/cart/items") @Transactional public Map<String,Object> add(@RequestHeader("X-Storefront-Session") String raw,@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody AddItem req){UUID s=resolveSession(raw,authorization);if(req.variantId==null||req.quantity<1||req.quantity>20)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid product variant and quantity from 1–20");ensureSession(s);List<Map<String,Object>> v=db.queryForList("SELECT stock_quantity FROM product_variants WHERE id=? AND active=true",req.variantId);if(v.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product variant not found in the active catalog");int stock=((Number)v.get(0).get("stock_quantity")).intValue();if(req.quantity>stock)throw new ResponseStatusException(HttpStatus.CONFLICT,"Only "+stock+" units are available for this selection");db.update("INSERT INTO storefront_cart_items(session_id,variant_id,quantity) VALUES(?,?,?) ON CONFLICT(session_id,variant_id) DO UPDATE SET quantity= EXCLUDED.quantity,updated_at=now()",s,req.variantId,req.quantity);return cartData(s);}
  @PatchMapping("/commerce/cart/items/{variantId}") @Transactional public Map<String,Object> update(@RequestHeader("X-Storefront-Session") String raw,@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable UUID variantId,@RequestBody Qty req){UUID s=resolveSession(raw,authorization);if(req.quantity<1||req.quantity>20)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Quantity must be 1–20");Integer stock=db.queryForObject("SELECT stock_quantity FROM product_variants WHERE id=? AND active=true",Integer.class,variantId);if(stock==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Variant not found");if(req.quantity>stock)throw new ResponseStatusException(HttpStatus.CONFLICT,"Quantity exceeds stock");int n=db.update("UPDATE storefront_cart_items SET quantity=?,updated_at=now() WHERE session_id=? AND variant_id=?",req.quantity,s,variantId);if(n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Cart item not found");return cartData(s);}
  @DeleteMapping("/commerce/cart/items/{variantId}") public Map<String,Object> remove(@RequestHeader("X-Storefront-Session") String raw,@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable UUID variantId){UUID s=resolveSession(raw,authorization);db.update("DELETE FROM storefront_cart_items WHERE session_id=? AND variant_id=?",s,variantId);return cartData(s);}
  @DeleteMapping("/commerce/cart") public Map<String,Object> clear(@RequestHeader("X-Storefront-Session") String raw,@RequestHeader(value="Authorization",required=false) String authorization){UUID s=resolveSession(raw,authorization);db.update("DELETE FROM storefront_cart_items WHERE session_id=?",s);return cartData(s);}

  /**
   * Turns the bag into an order and holds stock for it.
   *
   * <p>Three things about this method are load-bearing for the payment flow:
   *
   * <ol>
   *   <li>The order is created as {@code PENDING_PAYMENT}, not {@code PLACED}.
   *       "Placed" reads as a completed order in order history; an order that
   *       exists but has not been paid for has to say so.</li>
   *   <li>Stock is <em>held</em>, not sold. {@code reserveInventory} removes the
   *       units from the sellable count and records a RESERVED hold that
   *       {@link PaymentLifecycleService} either commits on a verified PAID or
   *       releases on a failure. The deduction here is therefore temporary by
   *       construction and is undone, with the correct quantity, on every
   *       unsuccessful outcome.</li>
   *   <li>The bag is deliberately NOT cleared. It used to be emptied here, which
   *       meant a failed or abandoned payment left the shopper with an empty bag
   *       for items they had not actually bought. The purchased lines are now
   *       removed by the settlement step, which knows which session they came
   *       from and can therefore leave unrelated lines alone.</li>
   * </ol>
   */
  @PostMapping("/commerce/checkout") @Transactional public Map<String,Object> checkout(@RequestHeader("X-Storefront-Session") String raw,@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody Checkout req){
    UUID customerId=authorization==null?null:auth.subject(authorization);
    UUID s=resolveSession(raw,authorization);

    // Lock cart rows and validate stock
    List<Map<String,Object>> rows=db.queryForList(
      "SELECT ci.variant_id,ci.quantity,p.id product_id,p.name,p.price,p.image_url product_image,v.image_url variant_image,v.sku,v.size,v.color,v.stock_quantity " +
      "FROM storefront_cart_items ci JOIN product_variants v ON v.id=ci.variant_id JOIN products p ON p.id=v.product_id " +
      "WHERE ci.session_id=? AND p.active AND v.active FOR UPDATE OF v",s);
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cart is empty");

    BigDecimal subtotal=BigDecimal.ZERO;
    for(var r:rows){
      int q=((Number)r.get("quantity")).intValue(),stock=((Number)r.get("stock_quantity")).intValue();
      if(q>stock)throw new ResponseStatusException(HttpStatus.CONFLICT,"Insufficient stock for "+r.get("name"));
      subtotal=subtotal.add(((BigDecimal)r.get("price")).multiply(BigDecimal.valueOf(q)));
    }
    BigDecimal shipping=subtotal.compareTo(new BigDecimal("2500"))>=0?BigDecimal.ZERO:new BigDecimal("99.00");
    BigDecimal total=subtotal.add(shipping);
    String orderNo="AO-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
    UUID orderId=UUID.randomUUID();

    // Create order. storefront_session_id is recorded so that settlement can
    // remove exactly the bag lines this order covered.
    db.update("INSERT INTO customer_orders(id,order_number,user_id,storefront_session_id,customer_name,email,phone,address_line1,address_line2,city,state,postal_code,country,subtotal,shipping_amount,total_amount,currency,order_status,payment_status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?, ?,?, 'INR','PENDING_PAYMENT','PENDING')",
      orderId,orderNo,customerId,s,req.name,req.email,req.phone,req.addressLine1,req.addressLine2,req.city,req.state,req.postalCode,req.country==null?"IN":req.country,subtotal,shipping,total);

    // Create order items and hold inventory
    for(var r:rows){
      UUID variant=(UUID)r.get("variant_id");
      int q=((Number)r.get("quantity")).intValue();
      // Snapshot the image at order time (variant image preferred, product image
      // otherwise) so order history keeps showing the right picture even if the
      // catalog image is later changed or the product removed.
      Object variantImage=r.get("variant_image"),productImage=r.get("product_image");
      Object snapshot=variantImage!=null?variantImage:productImage;
      db.update("INSERT INTO order_items(order_id,product_id,variant_id,product_name,sku,selected_size,selected_color,unit_price,quantity,line_total,product_image_url) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        orderId,r.get("product_id"),variant,r.get("name"),r.get("sku"),r.get("size"),r.get("color"),r.get("price"),q,((BigDecimal)r.get("price")).multiply(BigDecimal.valueOf(q)),snapshot);
      // Keep the catalog "Popularity" ranking in step with real sales.
      db.update("UPDATE products SET popularity_score = popularity_score + ? WHERE id = ?", q, r.get("product_id"));
      // Hold inventory. This is a temporary hold, not a sale.
      if(!inventoryService.reserveInventory(orderId,variant,q))
        throw new ResponseStatusException(HttpStatus.CONFLICT,"Stock changed during checkout; please retry");
    }

    return orderForOwner(orderId,customerId);
  }

  /**
   * Order history for the signed-in customer.
   *
   * <p>The customer id is taken from the bearer token and never from a request
   * parameter. This endpoint used to declare an unannotated {@code UUID uid}
   * parameter, which Spring binds as an optional query parameter that no caller
   * ever sends: the token was received and then ignored, and the query ran as
   * {@code WHERE user_id = null}. It therefore returned an empty list for every
   * customer, which the account page could not distinguish from having no orders.
   * Deriving the id from the token also closes the hole where any authenticated
   * caller could read another customer's orders by passing their id.
   *
   * <p>Returns {@code { "orders": [...] }} — a named envelope rather than a bare
   * array — so the shape can carry paging metadata later without breaking clients.
   * An empty list here means genuinely no orders, and is reported as such.
   */
  @GetMapping("/commerce/orders")
  public Map<String,Object> orders(@RequestHeader(value="Authorization",required=false) String authorization,
                                   @RequestParam(value="year",required=false) Integer year,
                                   @RequestParam(value="page",defaultValue="0") int page,
                                   @RequestParam(value="size",defaultValue="20") int size){
    UUID uid=auth.subject(authorization==null?"":authorization);
    int limit=Math.min(Math.max(size,1),100);
    int offset=Math.max(page,0)*limit;

    StringBuilder sql=new StringBuilder(
      "SELECT co.id, co.order_number AS \"orderNumber\", co.total_amount AS total, co.currency, "+
      "co.order_status AS status, co.payment_status AS \"paymentStatus\", co.created_at AS \"createdAt\", "+
      "(SELECT COUNT(*) FROM order_items oi WHERE oi.order_id=co.id) AS \"itemCount\", "+
      "(SELECT oi.product_name FROM order_items oi WHERE oi.order_id=co.id ORDER BY oi.id LIMIT 1) AS \"firstItemName\", "+
      "(SELECT COALESCE(NULLIF(btrim(oi.product_image_url),''), pv.image_url, p.image_url) "+
      "   FROM order_items oi "+
      "   LEFT JOIN product_variants pv ON pv.id=oi.variant_id "+
      "   LEFT JOIN products p ON p.id=oi.product_id "+
      "  WHERE oi.order_id=co.id ORDER BY oi.id LIMIT 1) AS \"image\" "+
      "FROM customer_orders co WHERE co.user_id=?");
    List<Object> args=new ArrayList<>();
    args.add(uid);
    if(year!=null){sql.append(" AND EXTRACT(YEAR FROM co.created_at)=?");args.add(year);}
    sql.append(" ORDER BY co.created_at DESC, co.id DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);

    List<Map<String,Object>> list=db.queryForList(sql.toString(),args.toArray());

    // The count has to describe the same set of rows the list came from, otherwise a
    // year filter reports the whole history while showing one year and the account
    // page cannot tell the customer how much is left to load.
    Integer countForQuery=db.queryForObject(
      "SELECT COUNT(*) FROM customer_orders co WHERE co.user_id=?"+
      (year!=null?" AND EXTRACT(YEAR FROM co.created_at)=?":""),
      year!=null?new Object[]{uid,year}:new Object[]{uid},Integer.class);
    Integer allTime=db.queryForObject("SELECT COUNT(*) FROM customer_orders WHERE user_id=?",Integer.class,uid);

    return Map.of(
      "orders",list,
      "count",list.size(),
      "total",countForQuery==null?0:countForQuery,
      "allTimeTotal",allTime==null?0:allTime,
      "page",page,
      "size",limit);
  }

  @GetMapping("/commerce/orders/{id}") public Map<String,Object> order(@PathVariable UUID id,@RequestHeader(value="Authorization",required=false) String authorization){return orderForOwner(id,auth.subject(authorization==null?"":authorization));}

  /**
   * A single order, scoped to its owner.
   *
   * <p>The {@code user_id} predicate is the authorisation check: a customer who
   * guesses another order id receives a 404 rather than someone else's address
   * and purchase history.
   */
  private Map<String,Object> orderForOwner(UUID id,UUID uid){
    List<Map<String,Object>> list=db.queryForList(
      "SELECT id,order_number AS \"orderNumber\",customer_name AS \"customerName\",email,phone," +
      "address_line1 AS \"addressLine1\",address_line2 AS \"addressLine2\",city,state,postal_code AS \"postalCode\"," +
      "subtotal,shipping_amount AS shipping,total_amount AS total,currency,order_status AS status,payment_status AS \"paymentStatus\",created_at AS \"createdAt\" " +
      "FROM customer_orders WHERE id=? AND user_id=?",id,uid);
    if(list.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
    Map<String,Object> o=new HashMap<>(list.get(0));
    o.put("items",db.queryForList(
      // The snapshot is authoritative. The joins are only a fallback for orders
      // placed before snapshots existed; they are LEFT JOINs so a retired
      // product or variant never breaks an existing order's history.
      "SELECT oi.product_name AS name,oi.sku,oi.selected_size AS size,oi.selected_color AS color,oi.unit_price AS price,oi.quantity,oi.line_total AS total,COALESCE(NULLIF(btrim(oi.product_image_url),''),v.image_url,p.image_url) AS image,oi.variant_id AS \"variantId\",oi.product_id AS \"productId\" " +
      "FROM order_items oi LEFT JOIN product_variants v ON v.id=oi.variant_id LEFT JOIN products p ON p.id=oi.product_id WHERE oi.order_id=?",id));
    // The payment return page and the order page both need to know whether a
    // further payment attempt is meaningful for this order.
    String paymentStatus=String.valueOf(o.get("paymentStatus"));
    o.put("paid","PAID".equals(paymentStatus));
    o.put("retryEligible",Set.of("PENDING","FAILED","CANCELLED","EXPIRED").contains(paymentStatus));
    return o;
  }

  @PostMapping("/commerce/orders/{id}/cancel") @Transactional public Map<String,Object> cancel(@PathVariable UUID id,@RequestHeader(value="Authorization",required=false) String authorization){
    UUID uid=auth.subject(authorization==null?"":authorization);
    String status;
    try{status=db.queryForObject("SELECT order_status FROM customer_orders WHERE id=? AND user_id=? FOR UPDATE",String.class,id,uid);}catch(Exception e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");}
    if(!Set.of("PENDING_PAYMENT","PLACED","CONFIRMED","PROCESSING").contains(status))throw new ResponseStatusException(HttpStatus.CONFLICT,"Order can no longer be cancelled");
    // Release every held line for this order in one call. This used to sit inside
    // a loop over the order's items, which asked for the same release once per
    // line; it happened to be masked by the RESERVED filter, but it made the
    // number of stock returns depend on how many lines the order had.
    inventoryService.releaseReservation(id,"Order cancelled by the customer");
    db.update("UPDATE customer_orders SET order_status='CANCELLED',updated_at=now() WHERE id=? AND user_id=?",id,uid);
    return orderForOwner(id,uid);
  }

  @PatchMapping("/admin/commerce/orders/{id}/status") public Map<String,Object> status(@PathVariable UUID id,@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody Status req){
    if(!auth.isAdmin(authorization==null?"":authorization))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Admin role required");
    Set<String> allowed=Set.of("CONFIRMED","PROCESSING","SHIPPED","DELIVERED","CANCELLED");
    if(!allowed.contains(req.status))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported status");
    int n=db.update("UPDATE customer_orders SET order_status=?,updated_at=now() WHERE id=? AND order_status<>'CANCELLED'",req.status,id);
    if(n==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Order not found or cancelled");
    return db.queryForMap("SELECT id,order_number AS \"orderNumber\",order_status AS status,payment_status AS \"paymentStatus\" FROM customer_orders WHERE id=?",id);
  }

  /* ------------------------------------------------------------- wishlist */

  /**
   * The signed-in customer's wishlist.
   *
   * <p>The wishlist used to exist only as a localStorage array, so it belonged to
   * the browser rather than the account: signing in on a second device showed
   * nothing, and two accounts sharing a machine shared a list. It is now read from
   * the account, so it is the same list everywhere and nobody else's.
   *
   * <p>A guest has no account to own a list, so this is 401 rather than an empty
   * result: an empty array would be indistinguishable from a saved list.
   */
  @GetMapping("/commerce/wishlist")
  public Map<String,Object> wishlist(@RequestHeader(value="Authorization",required=false) String authorization){
    UUID uid=auth.subject(authorization==null?"":authorization);
    List<Map<String,Object>> rows=db.queryForList(
      "SELECT w.product_id AS \"productId\", p.slug, p.name, p.price, p.image_url AS \"imageUrl\", w.created_at AS \"createdAt\" "+
      "FROM customer_wishlist w JOIN products p ON p.id=w.product_id "+
      "WHERE w.customer_id=? ORDER BY w.created_at DESC",uid);
    return Map.of("items",rows,"count",rows.size());
  }

  /**
   * Saves a piece to the wishlist, or removes it when already saved.
   *
   * <p>Toggling server-side keeps the stored list and the badge in step without the
   * browser having to be the authority: the response reports the state that was
   * actually persisted, so a second tab cannot leave the icon showing the wrong
   * thing.
   */
  @PostMapping("/commerce/wishlist/{productId}")
  @Transactional
  public Map<String,Object> toggleWishlist(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable UUID productId){
    UUID uid=auth.subject(authorization==null?"":authorization);
    if(db.queryForObject("SELECT COUNT(*) FROM products WHERE id=? AND active=true",Integer.class,productId)==0)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");
    int removed=db.update("DELETE FROM customer_wishlist WHERE customer_id=? AND product_id=?",uid,productId);
    if(removed==0){db.update("INSERT INTO customer_wishlist(customer_id,product_id) VALUES(?,?) ON CONFLICT(customer_id,product_id) DO NOTHING",uid,productId);}
    return Map.of("saved",removed==0);
  }

  public static class AddItem{public UUID variantId;public int quantity;}
  public static class Qty{public int quantity;}
  public static class Checkout{public String name,email,phone,addressLine1,addressLine2,city,state,postalCode,country;}
  public static class Status{public String status;}
}
