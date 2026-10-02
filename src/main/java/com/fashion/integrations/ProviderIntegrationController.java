package com.fashion.integrations;

import com.fashion.commerce.InventoryService;
import com.fashion.commerce.PaymentLifecycleService;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.time.Duration;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Cashfree Payments and Shiprocket adapter endpoints. Credentials are environment-only; never expose secrets to the browser. */
@RestController
@RequestMapping("/api/v1/integrations")
@CrossOrigin(origins="${app.cors-origin:http://localhost:3000}")
public class ProviderIntegrationController {

  @Value("${providers.cashfree.base-url:https://sandbox.cashfree.com/pg}") private String cashfreeUrl;
  // Credentials come from configuration only: application.yml binds these to
  // CASHFREE_APP_ID / CASHFREE_SECRET_KEY and DotEnvEnvironmentPostProcessor
  // sources them from the local .env. They used to be literals in this file with
  // the configured values preferred and the literals as a last-resort fallback.
  // That fallback is removed: it meant the sandbox secret travelled with the
  // source, and rotating the credential silently did nothing whenever the
  // environment variable was unset - the exact failure mode a secret in source
  // is supposed to prevent. An unconfigured merchant now fails closed.
  @Value("${providers.cashfree.app-id:}") private String configuredAppId;
  @Value("${providers.cashfree.secret-key:}") private String configuredSecret;

  @Value("${providers.cashfree.api-version:2023-08-01}") private String cashfreeVersion;
  @Value("${providers.shiprocket.base-url:https://apiv2.shiprocket.in/v1/external}") private String shiprocketUrl;
  @Value("${providers.shiprocket.email:}") private String shiprocketEmail;
  @Value("${providers.shiprocket.password:}") private String shiprocketPassword;
  @Value("${providers.shiprocket.pickup-postcode:}") private String shiprocketPickupPostcode;
  @Value("${providers.shiprocket.default-weight-kg:}") private String shiprocketDefaultWeight;
  // app.frontend-base-url is the single configured storefront host. This used to
  // read the password-reset specific property, so the Cashfree return URL was
  // built from a value whose only documented purpose was reset emails.
  @Value("${app.frontend-base-url:http://localhost:3000}") private String frontendBaseUrl;
  @Value("${app.payment-result-path:/payment/return}") private String paymentResultPath;
  // The publicly reachable origin of THIS service. Cashfree needs an absolute URL
  // for notify_url, and it is the backend origin rather than the storefront one.
  @Value("${app.public-api-base-url:}") private String publicApiBaseUrl;
  @Value("${providers.cashfree.webhook-path:/api/v1/integrations/cashfree/webhook}") private String cashfreeWebhookPath;
  @Value("${SHIPROCKET_MODE:test}")
  private String shiprocketMode;

  private boolean isShiprocketTestMode() {
    return "test".equalsIgnoreCase(shiprocketMode);
  }

  private final JdbcTemplate db;
  private final RestClient http;
  private final PaymentLifecycleService payments;
  private final InventoryService inventory;
  private volatile String shiprocketCachedToken;
  private volatile Instant shiprocketTokenExpiresAt=Instant.EPOCH;
  private final Map<String,Map<String,Object>> serviceabilityCache=new java.util.concurrent.ConcurrentHashMap<>();
  private final Map<String,Instant> serviceabilityCacheExpiry=new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Every Cashfree call resolves credentials through here, so there is exactly one
   * place where the fallback to the built-in sandbox key is applied.
   */
  // These return the configured value, or empty when unset. They deliberately
  // do not fall back to a built-in credential and do not throw here: the
  // single place that decides "payments are unavailable" is cashfreeConfigured(),
  // which reports a customer-safe 503. Returning empty means the same guard also
  // covers these accessors, so a missing environment variable can never reach the
  // Cashfree API as a blank or stale credential.
  private String appId(){return configuredAppId==null?"":configuredAppId.trim();}
  private String secretKey(){return configuredSecret==null?"":configuredSecret.trim();}

  public ProviderIntegrationController(JdbcTemplate db,
                                       PaymentLifecycleService payments,
                                       InventoryService inventory) {
    this.db = db;
    this.payments = payments;
    this.inventory = inventory;

    SimpleClientHttpRequestFactory factory =
            new SimpleClientHttpRequestFactory();

    factory.setConnectTimeout(Duration.ofSeconds(10));
    factory.setReadTimeout(Duration.ofSeconds(30));

    this.http = RestClient.builder()
            .requestFactory(factory)
            .build();
  }
  private void cashfreeConfigured(){if(appId().isBlank()||secretKey().isBlank()){System.err.println("[cashfree] Configuration missing: CASHFREE_APP_ID and CASHFREE_SECRET_KEY are both required");throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Payment is temporarily unavailable. Please try again later.");}}
  private void shiprocketConfigured(){if(shiprocketEmail.isBlank()||shiprocketPassword.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configure SHIPROCKET_EMAIL and SHIPROCKET_PASSWORD first");}

  @GetMapping("/shiprocket/serviceability") public Map<String,Object> serviceability(@RequestParam String deliveryPincode,@RequestParam(defaultValue="false") boolean cod,@RequestParam(required=false) Double weight){
    // PIN format is validated first in every mode, so a malformed code is always a
    // client error regardless of which courier integration the environment runs.
    String destination=deliveryPincode==null?"":deliveryPincode.trim();
    if(!destination.matches("\\d{6}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a valid 6-digit Indian PIN code.");
    double parcelWeight=requestedWeight(weight);
    if(!Double.isFinite(parcelWeight)||parcelWeight<=0||parcelWeight>70)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Package weight must be between 0 and 70 kg.");
    // Local / investor testing mode (SHIPROCKET_MODE=test, the default). Any
    // syntactically valid Indian PIN code is reported as serviceable WITHOUT
    // contacting Shiprocket, so the checkout flow is exercisable end to end.
    // This is not a courier response: set SHIPROCKET_MODE=live and supply
    // SHIPROCKET_EMAIL / SHIPROCKET_PASSWORD to fall through to the real
    // Shiprocket serviceability lookup below.
    if(isShiprocketTestMode())return testModeServiceability(destination);
    shiprocketConfigured();
    if(shiprocketPickupPostcode==null||!shiprocketPickupPostcode.matches("\\d{6}"))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configure SHIPROCKET_PICKUP_PINCODE with your warehouse PIN code first.");
    String key=shiprocketPickupPostcode+":"+destination+":"+parcelWeight+":"+cod;Instant now=Instant.now();Map<String,Object> cached=serviceabilityCache.get(key);if(cached!=null&&now.isBefore(serviceabilityCacheExpiry.getOrDefault(key,Instant.EPOCH)))return cached;try{String token=shiprocketToken();var uri=UriComponentsBuilder.fromUriString(shiprocketUrl).path("/courier/serviceability/").queryParam("pickup_postcode",shiprocketPickupPostcode).queryParam("delivery_postcode",destination).queryParam("weight",parcelWeight).queryParam("cod",cod?1:0).build().toUri();Map response=http.get().uri(uri).header("Authorization","Bearer "+token).retrieve().body(Map.class);Map data=response==null?Map.of():response.get("data") instanceof Map<?,?> map?(Map<String,Object>)map:Map.of();List<?> couriers=data.get("available_courier_companies") instanceof List<?> list?list:List.of();Map<String,Object> selected=couriers.stream().filter(Map.class::isInstance).map(row->(Map<String,Object>)row).findFirst().orElse(Map.of());Object days=selected.get("estimated_delivery_days");Object etd=selected.get("etd");Map<String,Object> result=new LinkedHashMap<>();result.put("serviceable",!couriers.isEmpty());result.put("courierCount",couriers.size());result.put("estimatedDeliveryDays",days);result.put("estimatedDelivery",etd);result.put("message",!couriers.isEmpty()?"Delivery is available to this PIN code.":"Delivery is not available to this PIN code.");serviceabilityCache.put(key,result);serviceabilityCacheExpiry.put(key,now.plus(Duration.ofMinutes(15)));return result;}catch(RestClientResponseException ex){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"The delivery partner could not authorize the serviceability check.");}catch(ResourceAccessException ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"The delivery partner is temporarily unavailable. Please retry.");}}
  private double requestedWeight(Double weight){if(weight!=null)return weight;return isShiprocketTestMode()?1.0d:parseConfiguredWeight();}
  /** Local testing-mode answer. Deliberately isolated from the Shiprocket call path so production keeps the real lookup. */
  private Map<String,Object> testModeServiceability(String destination){Map<String,Object> result=new LinkedHashMap<>();result.put("serviceable",true);result.put("courierCount",0);result.put("estimatedDeliveryDays",null);result.put("estimatedDelivery",null);result.put("mode","test");result.put("message","Delivery available to this pincode.");return result;}
  private double parseConfiguredWeight(){try{double value=Double.parseDouble(shiprocketDefaultWeight);if(value>0&&value<=70)return value;}catch(Exception ignored){}throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configure SHIPROCKET_DEFAULT_PACKAGE_WEIGHT_KG or send the product's actual package weight.");}
  private synchronized String shiprocketToken(){if(shiprocketCachedToken!=null&&Instant.now().isBefore(shiprocketTokenExpiresAt.minus(Duration.ofHours(1))))return shiprocketCachedToken;try{Map response=http.post().uri(shiprocketUrl+"/auth/login").contentType(MediaType.APPLICATION_JSON).body(Map.of("email",shiprocketEmail,"password",shiprocketPassword)).retrieve().body(Map.class);String token=Objects.toString(response==null?null:response.get("token"),"");if(token.isBlank())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Shiprocket did not return an auth token");shiprocketCachedToken=token;shiprocketTokenExpiresAt=Instant.now().plus(Duration.ofDays(9));return token;}catch(RestClientResponseException ex){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"The delivery partner could not authorize the serviceability check.");}catch(ResourceAccessException ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"The delivery partner is temporarily unavailable. Please retry.");}}

  /**
   * Creates a Cashfree hosted checkout session for an existing unpaid order.
   *
   * <p>Shared by the first attempt and by a retry so both produce an identical
   * session: same order, same amount, same return URL, same provider order id.
   * Because the provider order id is derived from the local order id, retrying
   * updates the existing {@code payment_transactions} row through its unique
   * index instead of accumulating a second transaction per attempt.
   */
  @PostMapping("/cashfree/orders/{orderId}/session")
  public Map<String, Object> cashfreeSession(
          @PathVariable UUID orderId,
          @RequestBody(required = false) ReturnUrl body) {

    cashfreeConfigured();

    Map<String, Object> order = one(
        "SELECT order_number,total_amount,currency,customer_name," +
        "email,phone,payment_status " +
        "FROM customer_orders WHERE id=?",
        orderId
    );

    if (!payments.isRetryEligible(Objects.toString(order.get("payment_status"), "PENDING"))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Order payment is not pending"
      );
    }

    return createSessionForOrder(orderId, order, body != null ? body.returnUrl : null);
  }

  private Map<String, Object> createSessionForOrder(UUID orderId, Map<String, Object> order, String requestedReturnUrl) {

    String cfOrderId = payments.nextProviderOrderId(orderId);

    Map<String, Object> customer = new LinkedHashMap<>();
    customer.put("customer_id", orderId.toString());
    customer.put("customer_name",
        Objects.toString(order.get("customer_name"), "Customer"));
    customer.put("customer_email",
        Objects.toString(order.get("email"), ""));
    customer.put("customer_phone",
        Objects.toString(order.get("phone"), "9999999999"));

    Map<String, Object> payload = new LinkedHashMap<>();

    payload.put("order_id", cfOrderId);
    payload.put("order_amount",
        ((Number) order.get("total_amount")).doubleValue());
    payload.put("order_currency", order.get("currency"));
    payload.put("customer_details", customer);

    // The return URL is only ever used to send the customer back to the site, so
    // it is rebuilt from the configured storefront host. A caller-supplied value is
    // honoured because the storefront and the gateway must agree on the host, but a
    // blank one falls back to configuration rather than producing a relative URL
    // that Cashfree cannot redirect to.
    String returnUrl =
        requestedReturnUrl != null && !requestedReturnUrl.isBlank()
        ? requestedReturnUrl
        : frontendBaseUrl + paymentResultPath + "?orderId=" + orderId;

    System.out.println("Cashfree return URL: " + returnUrl);

    payload.put("order_meta", orderMeta(returnUrl));



    // Call Cashfree and handle provider failures.
    Map<String, Object> response;

    try {
      response = http.post()
          .uri(cashfreeUrl + "/orders")
          .header("x-client-id", appId())
          .header("x-client-secret", secretKey())
          .header("x-api-version", cashfreeVersion)
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(Map.class);

      if (response == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "Cashfree returned an empty response"
        );
      }

    } catch (RestClientResponseException ex) {

      // Cashfree returned an HTTP error, such as 400 or 504.
      HttpStatusCode providerStatus = ex.getStatusCode();

      // Do not expose provider response bodies or credentials.
      System.err.println(
          "Cashfree API error. HTTP status: "
          + providerStatus.value()
      );

      if (providerStatus.value() == 504) {
        throw new ResponseStatusException(
            HttpStatus.GATEWAY_TIMEOUT,
            "Cashfree payment gateway timed out. "
            + "Check Cashfree order status before retrying."
        );
      }

      if (providerStatus.is5xxServerError()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "Cashfree payment service is temporarily unavailable"
        );
      }

      if (providerStatus.value() == 401 ||
          providerStatus.value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "Cashfree rejected the API credentials or permissions"
        );
      }

      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY,
          "Cashfree rejected the payment session request. "
          + "Check the order details and Cashfree configuration."
      );

    } catch (ResourceAccessException ex) {

      // Connection timeout, read timeout, or network failure.
      System.err.println(
          "Cashfree connection or response timeout: "
          + ex.getClass().getSimpleName()
      );

      throw new ResponseStatusException(
          HttpStatus.GATEWAY_TIMEOUT,
          "Unable to reach Cashfree or receive a response. "
          + "Verify payment status before retrying."
      );
    }

    // Persist the successful Cashfree response.
    db.update(
        "INSERT INTO payment_transactions(" +
        "id,order_id,provider,provider_order_id,amount," +
        "currency,status,provider_payload,created_at,updated_at" +
        ") VALUES(?,?,'CASHFREE',?,?,?,'CREATED',?::jsonb,now(),now()) " +
        "ON CONFLICT(provider_order_id) DO UPDATE SET " +
        "provider_payload=EXCLUDED.provider_payload," +
        "updated_at=now()",
        UUID.randomUUID(),
        orderId,
        cfOrderId,
        order.get("total_amount"),
        order.get("currency"),
        json(response)
    );

    return response;
  }
  /**
   * Reconciles one order against Cashfree and returns the resulting state.
   *
   * <p>This is the only thing the payment return page trusts. The browser arriving
   * back from Cashfree proves nothing: a customer can edit the URL, replay a
   * success query string onto a cancelled order, or simply close the tab. So the
   * status is read from Cashfree's server API and handed to
   * {@link PaymentLifecycleService}, which is the only component allowed to move
   * an order or touch stock.
   *
   * <p>It previously handled exactly one outcome. If the order was not PAID it
   * returned {@code verified:false} and changed nothing at all, which is why a
   * failed payment left the order reading "Placed · Pending" and left its stock
   * hold in place permanently. Every terminal provider outcome is now reconciled.
   *
   * <p>Safe to call any number of times, from any number of tabs.
   */
  @GetMapping("/cashfree/orders/{orderId}/verify")
  public Map<String,Object> verifyCashfree(@PathVariable UUID orderId){
    cashfreeConfigured();

    List<Map<String,Object>> rows = db.queryForList(
        "SELECT provider_order_id FROM payment_transactions " +
        "WHERE order_id=? AND provider='CASHFREE' ORDER BY created_at DESC LIMIT 1", orderId);
    if (rows.isEmpty()) {
      // The order exists but no payment was ever started for it, so there is
      // nothing to reconcile. This is a normal state, not a server error.
      return payments.describe(orderId, "UNKNOWN", false, "VERIFY");
    }

    String cfId = Objects.toString(rows.get(0).get("provider_order_id"), "");
    if (cfId.isBlank()) {
      return payments.describe(orderId, "UNKNOWN", false, "VERIFY");
    }

    Map<String,Object> result;
    try {
      result = http.get()
          .uri(cashfreeUrl + "/orders/" + cfId)
          .header("x-client-id", appId())
          .header("x-client-secret", secretKey())
          .header("x-api-version", cashfreeVersion)
          .retrieve()
          .body(Map.class);
    } catch (RestClientResponseException ex) {
      // The provider answered with an error. We could not read the status, so no
      // local state may change: the customer is told to try again rather than
      // being shown a guess.
      System.err.println("[cashfree] verify HTTP " + ex.getStatusCode().value() + " for " + cfId);
      return unavailable(orderId, "The payment provider could not be reached. Please try again.");
    } catch (ResourceAccessException ex) {
      System.err.println("[cashfree] verify timeout for " + cfId);
      return unavailable(orderId, "The payment provider did not respond. Please try again.");
    }

    if (result == null) {
      return unavailable(orderId, "The payment provider returned an empty response. Please try again.");
    }

    System.out.println(
    "[CASHFREE VERIFY] order_status=" + result.get("order_status")
    + ", payment_status=" + result.get("payment_status")
);

    String canonical = payments.mapProviderStatus(
        Objects.toString(result.get("order_status"), ""),
        Objects.toString(result.get("payment_status"), ""));
    return payments.reconcile(orderId, canonical, json(result), "VERIFY");
  }

  /**
   * Cashfree webhook. Configure this URL in the Cashfree dashboard.
   *
   * <p>The signature is verified against the raw body, then the event is
   * reconciled through the same path the verify endpoint uses. Previously this
   * endpoint only acknowledged delivery and told the caller to verify separately,
   * so a payment that Cashfree had already settled sat unreconciled until the
   * customer happened to return to the site — and a payment that failed was never
   * noticed at all, stranding its stock hold.
   *
   * <p>Always answers 200 for a correctly signed event, including for orders it
   * cannot match. A non-2xx would make Cashfree retry an event that can never
   * succeed.
   */
  @PostMapping(value="/cashfree/webhook",consumes=MediaType.APPLICATION_JSON_VALUE)
  public Map<String,Object> cashfreeWebhook(
      @RequestHeader(value="x-webhook-signature",required=false) String signature,
      @RequestHeader(value="x-webhook-timestamp",required=false) String timestamp,
      @RequestBody String raw){

    cashfreeConfigured();
    if (signature==null || timestamp==null || !constantTime(signature, hmac(timestamp+raw, secretKey()))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Cashfree webhook signature");
    }

    Map<String,Object> event = parse(raw);
    String eventId = Objects.toString(event.get("event_id"), "");
    String eventType = Objects.toString(event.get("type"), "");

    // Cashfree retries anything it did not get a 2xx for, and browsers can deliver
    // the same event twice. The provider's own event id is the deduplication key.
    if (!eventId.isBlank() && alreadyHandled(eventId)) {
      return Map.of("received",true,"duplicate",true,"eventId",eventId);
    }

    Map<String,Object> data = asMap(event.get("data"));
    Map<String,Object> orderData = asMap(data.get("order"));
    Map<String,Object> paymentData = asMap(data.get("payment"));

    String cfOrderId = Objects.toString(orderData.get("order_id"), "");
    if (cfOrderId.isBlank()) {
      markHandled(eventId);
      return Map.of("received",true,"ignored","no order id in event");
    }

    List<Map<String,Object>> rows = db.queryForList(
        "SELECT co.id FROM customer_orders co " +
        "JOIN payment_transactions pt ON pt.order_id = co.id " +
        "WHERE pt.provider_order_id = ? AND pt.provider = 'CASHFREE'", cfOrderId);
    if (rows.isEmpty()) {
      // Not one of ours. Recorded so the event is not reprocessed, and reported
      // as received so Cashfree stops retrying.
      storeEvent(null, cfOrderId, eventId, eventType, "UNKNOWN", raw);
      return Map.of("received",true,"ignored","unknown order");
    }

    UUID orderId = (UUID) rows.get(0).get("id");
    String canonical = payments.mapProviderStatus(
        Objects.toString(orderData.get("order_status"), ""),
        Objects.toString(paymentData.get("payment_status"), ""));

    Map<String,Object> state = payments.reconcile(orderId, canonical, raw, "WEBHOOK");

    // Record the provider's payment id for traceability, then mark the event done.
    String cfPaymentId = Objects.toString(paymentData.get("payment_id"), "");
    if (!cfPaymentId.isBlank()) {
      db.update("UPDATE payment_transactions SET provider_payment_id=COALESCE(provider_payment_id,?), updated_at=now() " +
          "WHERE order_id=? AND provider='CASHFREE'", cfPaymentId, orderId);
    }
    storeEvent(orderId, cfOrderId, eventId, eventType, canonical, raw);

    Map<String,Object> response = new LinkedHashMap<>(state);
    response.put("received", true);
    response.put("eventId", eventId);
    return response;
  }

  /**
   * Starts a new payment attempt for an order that has not been paid.
   *
   * <p>"Retry payment" used to be a link back to the payment return page, which
   * only re-read the status of the attempt that had just failed. It never opened
   * Cashfree again, so the customer had no way to actually pay. This endpoint
   * mints a fresh payment session for the <em>same</em> order: no second
   * {@code customer_orders} row, no duplicate order number, and no second stock
   * hold for the same lines.
   *
   * <p>Re-reserving is necessary rather than incidental. The previous failure
   * released the hold and put the units back on the shelf, so without taking the
   * hold again the customer could pay for stock that someone else has since
   * bought. Lines that still hold a reservation are left alone, so a retry that
   * races the original attempt cannot double-deduct.
   */
  @PostMapping("/cashfree/orders/{orderId}/retry")
  @Transactional
  public Map<String,Object> retryCashfree(@PathVariable UUID orderId,
                                          @RequestBody(required=false) ReturnUrl body){
    cashfreeConfigured();

    Map<String,Object> order = one(
        "SELECT order_number,total_amount,currency,customer_name,email,phone,payment_status,order_status " +
        "FROM customer_orders WHERE id=?", orderId);

    String paymentStatus = Objects.toString(order.get("payment_status"), "PENDING");
    if (!payments.isRetryEligible(paymentStatus)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This order's payment is already settled. Please contact our care team if something looks wrong.");
    }

    if (!inventory.reReserveForRetry(orderId)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Some reserved items are no longer available in the quantity ordered. Please contact our care team.");
    }

    // The attempt starts over from a clean local state, so the return page and
    // order history describe this attempt rather than the abandoned one.
    db.update("UPDATE customer_orders SET payment_status='PENDING', order_status='PAYMENT_PENDING', updated_at=now() " +
        "WHERE id=? AND payment_status <> 'PAID'", orderId);

    return createSessionForOrder(orderId, order,
        body != null ? body.returnUrl : null);
  }

  /**
   * Response for a provider that could not be consulted.
   *
   * <p>Deliberately reports the local state and an {@code error} string instead of
   * throwing: a provider outage is not an order failure, and the customer must be
   * able to come back and see the truth once it clears.
   */
  private Map<String,Object> unavailable(UUID orderId, String message){
    Map<String,Object> state = payments.describe(orderId, "UNKNOWN", false, "VERIFY");
    state.put("available", false);
    state.put("error", message);
    return state;
  }

  private boolean alreadyHandled(String eventId){
    try {
      Integer count = db.queryForObject("SELECT COUNT(*) FROM payment_events WHERE event_id=?", Integer.class, eventId);
      return count != null && count > 0;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private void markHandled(String eventId){
    if (eventId.isBlank()) return;
    try {
      db.update("INSERT INTO payment_events(id,provider,event_type,event_id,status,raw_payload,processed_at) " +
          "VALUES(?,'CASHFREE','UNMATCHED',?,'UNKNOWN','{}'::jsonb,now()) ON CONFLICT(event_id) DO NOTHING",
          UUID.randomUUID(), eventId);
    } catch (RuntimeException e) {
      System.err.println("[cashfree] could not mark event handled: " + e.getMessage());
    }
  }

  private void storeEvent(UUID orderId, String cfOrderId, String eventId,
                          String eventType, String status, String raw){
    if (eventId.isBlank()) return;
    try {
      db.update("INSERT INTO payment_events(id,order_id,provider,provider_order_id,event_type,event_id,status,raw_payload,processed_at) " +
          "VALUES(?,?,'CASHFREE',?,?,?,?,?::jsonb,now()) ON CONFLICT(event_id) DO NOTHING",
          UUID.randomUUID(), orderId, cfOrderId, eventType, eventId, status, raw);
    } catch (RuntimeException e) {
      System.err.println("[cashfree] could not store event: " + e.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String,Object> asMap(Object value){
    return value instanceof Map ? (Map<String,Object>) value : Map.of();
  }

  private Map<String,Object> parse(String raw){
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
    } catch (Exception e) {
      return Map.of();
    }
  }

  /**
   * Creates the shipment for an order.
   *
   * <p>Refuses to dispatch anything for an order that has not been paid. A shipment
   * for a failed or cancelled order means a courier collects stock the customer
   * never bought and a support ticket when they dispute the charge, so the payment
   * check is enforced here rather than left to whoever calls this endpoint.
   *
   * <p>Safe to call repeatedly: the shipment row is written through a unique index
   * on {@code order_id}, so a second call updates the existing shipment instead of
   * creating a duplicate with a second AWB.
   */
  @PostMapping("/shiprocket/orders/{orderId}/create") public Map<String,Object> createShipment(@PathVariable UUID orderId){

    String paymentStatus;
    try {
      paymentStatus = Objects.toString(
          db.queryForObject("SELECT payment_status FROM customer_orders WHERE id=?", String.class, orderId), "");
    } catch (org.springframework.dao.EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
    }
    if (!"PAID".equals(paymentStatus)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "A shipment can only be created once payment for this order is confirmed.");
    }

     // TEST MODE: Never call Shiprocket or create a real shipment.
    if (isShiprocketTestMode()) {
        Map<String, Object> response = new LinkedHashMap<>();

        response.put("status", "TEST_SHIPMENT_CREATED");
        response.put("order_id", orderId.toString());
        response.put("shipment_id", 999001);
        response.put("awb_code", "TEST123456");
        response.put("courier_name", "Demo Courier");
        response.put("message",
                "Mock shipment created. No real shipment was created.");

        return response;
    }


    shiprocketConfigured();Map<String,Object> o=one("SELECT * FROM customer_orders WHERE id=?",orderId);List<Map<String,Object>> items=db.queryForList("SELECT product_name,quantity,unit_price FROM order_items WHERE order_id=?",orderId);if(items.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Order has no line items");
    Map tokenResp=http.post().uri(shiprocketUrl+"/auth/login").contentType(MediaType.APPLICATION_JSON).body(Map.of("email",shiprocketEmail,"password",shiprocketPassword)).retrieve().body(Map.class);String token=Objects.toString(tokenResp.get("token"),"");if(token.isBlank())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Shiprocket did not return an auth token");
    List<Map<String,Object>> lines=new ArrayList<>();for(var i:items)lines.add(Map.of("name",i.get("product_name"),"units",i.get("quantity"),"selling_price",i.get("unit_price")));
    Map<String,Object> address=new LinkedHashMap<>();address.put("first_name",o.get("customer_name"));address.put("address",o.get("address_line1"));address.put("address_2",Objects.toString(o.get("address_line2"),""));address.put("city",o.get("city"));address.put("state",o.get("state"));address.put("country",o.get("country"));address.put("pincode",o.get("postal_code"));address.put("phone",o.get("phone"));address.put("email",o.get("email"));
    Map<String,Object> payload=new LinkedHashMap<>();payload.put("order_id",o.get("order_number"));payload.put("order_date",String.valueOf(o.get("created_at")));payload.put("pickup_location","Primary");payload.put("billing_customer_name",o.get("customer_name"));payload.put("billing_address",o.get("address_line1"));payload.put("billing_address_2",Objects.toString(o.get("address_line2"),""));payload.put("billing_city",o.get("city"));payload.put("billing_state",o.get("state"));payload.put("billing_country",o.get("country"));payload.put("billing_pincode",o.get("postal_code"));payload.put("billing_phone",o.get("phone"));payload.put("billing_email",o.get("email"));payload.put("shipping_is_billing",true);payload.put("order_items",lines);payload.put("payment_method","Prepaid");payload.put("sub_total",((Number)o.get("subtotal")).doubleValue());payload.put("length",20);payload.put("breadth",15);payload.put("height",5);payload.put("weight",0.5);
    Map response=http.post().uri(shiprocketUrl+"/orders/create/adhoc").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(Map.class);Object shipmentId=response.get("shipment_id"), srOrder=response.get("order_id");db.update("INSERT INTO shipments(id,order_id,provider,provider_order_id,shipment_id,status,provider_payload,created_at,updated_at) VALUES(?,?,'SHIPROCKET',?,?,?,?::jsonb,now(),now()) ON CONFLICT(order_id) DO UPDATE SET provider_order_id=EXCLUDED.provider_order_id,shipment_id=EXCLUDED.shipment_id,status=EXCLUDED.status,provider_payload=EXCLUDED.provider_payload,updated_at=now()",UUID.randomUUID(),orderId,Objects.toString(srOrder,""),Objects.toString(shipmentId,""),"CREATED",json(response));return response;
  }
  @GetMapping("/shiprocket/orders/{orderId}/tracking")
  public Map<String,Object> track(@PathVariable UUID orderId){



        // TEST MODE: Return mock tracking without Shiprocket.
    if (isShiprocketTestMode()) {
        Map<String, Object> response = new LinkedHashMap<>();

        response.put("tracking_data", Map.of(
                "track_status", 1,
                "shipment_status", "IN TRANSIT",
                "shipment_track", List.of(
                        Map.of(
                                "awb_code", "TEST123456",
                                "courier_name", "Demo Courier",
                                "current_status", "IN TRANSIT",
                                "current_location", "Mumbai",
                                "delivered_date", ""
                        )
                ),
                "shipment_track_activities", List.of(
                        Map.of(
                                "date", "2026-09-26 10:00:00",
                                "activity", "Shipment picked up",
                                "location", "Mumbai",
                                "sr-status", "In Transit"
                        )
                )
        ));

        response.put("message",
                "Demo tracking data. No real shipment exists.");

        return response;
    }


    shiprocketConfigured();Map<String,Object>s=one("SELECT shipment_id FROM shipments WHERE order_id=?",orderId);Map token=http.post().uri(shiprocketUrl+"/auth/login").contentType(MediaType.APPLICATION_JSON).body(Map.of("email",shiprocketEmail,"password",shiprocketPassword)).retrieve().body(Map.class);String auth=Objects.toString(token.get("token"),"");return http.get().uri(shiprocketUrl+"/courier/track/shipment/"+s.get("shipment_id")).header("Authorization","Bearer "+auth).retrieve().body(Map.class);}

  private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>>r=db.queryForList(sql,args);if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Record not found");return r.get(0);}private String json(Object o){try{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
  /**
 * Builds the order_meta block sent to Cashfree.
 *
 * <p>notify_url is what makes payment outcome server-to-server. Without it Cashfree
 * has no address to call, so a customer who completes payment and closes the browser
 * leaves the order pending until the hold sweeper expires it - the payment is
 * captured but the stock is handed back. The URL is omitted entirely when no public
 * origin is configured, because sending Cashfree a placeholder would make it deliver
 * events to a host that does not exist.
 */
private Map<String,Object> orderMeta(String returnUrl){
    String notify = notifyUrl();
    return notify==null?Map.of("return_url",returnUrl)
        :Map.of("return_url",returnUrl,"notify_url",notify);
}

/**
 * Absolute webhook URL, or null when this deployment has no public origin set.
 */
private String notifyUrl(){
    if(publicApiBaseUrl==null||publicApiBaseUrl.isBlank())return null;
    String base=publicApiBaseUrl.endsWith("/")
        ?publicApiBaseUrl.substring(0,publicApiBaseUrl.length()-1)
        :publicApiBaseUrl;
    String path=cashfreeWebhookPath==null||cashfreeWebhookPath.isBlank()
        ?"/api/v1/integrations/cashfree/webhook":cashfreeWebhookPath;
    return base+(path.startsWith("/")?path:"/"+path);
}

private String hmac(String data,String secret){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}private boolean constantTime(String a,String b){return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
  public static class ReturnUrl{public String returnUrl;}
}
