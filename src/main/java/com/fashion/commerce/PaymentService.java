package com.fashion.commerce;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Cashfree server-API access and webhook signature checking.
 *
 * <h2>Why the state machine moved out</h2>
 * This class used to own the payment status transition and the inventory
 * consequences of that transition. A second, parallel copy of that logic lived in
 * the integration controller, and the two disagreed: this one advanced
 * {@code order_status} only from {@code PLACED}, while checkout had started writing
 * {@code PENDING_PAYMENT}, so its confirm step silently matched nothing. Neither
 * copy was reachable from the endpoints the browser actually calls, which is why
 * stock held by a failed payment was never returned.
 *
 * <p>All of that now lives in {@link PaymentLifecycleService}, which is the single
 * writer of payment state and the only caller of {@link InventoryService}. This
 * class is now only the transport: credentials, signing, and talking to Cashfree.
 * Keeping a second copy of the rules here would guarantee they drift again.
 */
@Service
public class PaymentService {

    private final JdbcTemplate db;
    private final RestClient http;
    private final PaymentLifecycleService lifecycle;

    @Value("${app.frontend-base-url:http://localhost:3000}") private String frontendBaseUrl;
    @Value("${app.payment-result-path:/payment/return}") private String paymentResultPath;

    @Value("${providers.cashfree.base-url:https://sandbox.cashfree.com/pg}") private String cashfreeUrl;
    @Value("${providers.cashfree.app-id:}") private String cashfreeAppId;
    @Value("${providers.cashfree.secret-key:}") private String cashfreeSecret;
    @Value("${providers.cashfree.api-version:2023-08-01}") private String cashfreeVersion;
    @Value("${providers.cashfree.webhook-path:/api/v1/integrations/cashfree/webhook}")
    private String cashfreeWebhookPath;

    @Value("${providers.cashfree.mode:}") private String cashfreeMode;

    public PaymentService(JdbcTemplate db, PaymentLifecycleService lifecycle) {
        this.db = db;
        this.lifecycle = lifecycle;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Customer-facing message when Cashfree is not configured.
     * NEVER expose the real credential error to the browser.
     */
    public String getCashfreeNotConfiguredMessage() {
        return "Payment is temporarily unavailable. Please try again later.";
    }

    /** Canonical mapping of a provider order/payment status pair. */
    public String mapCashfreeStatus(String orderStatus, String paymentStatus) {
        return lifecycle.mapProviderStatus(orderStatus, paymentStatus);
    }

    /**
     * Reconciles a webhook event against the local order.
     *
     * <p>Signature verification happens first and is mandatory: an unsigned POST to
     * this path would otherwise be able to mark any order paid or release any
     * order's stock. The status transition itself is delegated so this path and the
     * verify endpoint can never disagree.
     */
    @Transactional
    public Map<String, Object> processCashfreeWebhook(String signature, String timestamp, String rawBody) {
        if (!cashfreeConfigured()) {
            System.err.println("[payment] Webhook received but Cashfree not configured");
            return Map.of("received", true, "message", "Payment service unavailable");
        }

        if (signature == null || timestamp == null
                || !constantTimeEquals(signature, hmac(timestamp + rawBody, cashfreeSecret))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Cashfree webhook signature");
        }

        Map<String, Object> event = parseJson(rawBody);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = event.get("data") instanceof Map ? (Map<String, Object>) event.get("data") : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> orderData = data.get("order") instanceof Map ? (Map<String, Object>) data.get("order") : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> paymentData = data.get("payment") instanceof Map ? (Map<String, Object>) data.get("payment") : Map.of();

        String cfOrderId = Objects.toString(orderData.get("order_id"), "");
        String eventId = Objects.toString(event.get("event_id"), "");
        String eventType = Objects.toString(event.get("type"), "");

        if (!eventId.isBlank() && eventAlreadyProcessed(eventId)) {
            return Map.of("received", true, "duplicate", true, "message", "Event already processed");
        }

        List<Map<String, Object>> orders = db.queryForList(
                "SELECT co.id FROM customer_orders co " +
                    "JOIN payment_transactions pt ON pt.order_id = co.id " +
                    "WHERE pt.provider_order_id = ? AND pt.provider = 'CASHFREE'",
                cfOrderId);
        if (orders.isEmpty()) {
            storePaymentEvent(null, cfOrderId, Objects.toString(paymentData.get("payment_id"), ""),
                    eventType, eventId, "UNKNOWN", rawBody);
            return Map.of("received", true, "message", "Order not found for this webhook");
        }

        UUID orderId = (UUID) orders.get(0).get("id");
        String cfPaymentId = Objects.toString(paymentData.get("payment_id"), "");
        String canonical = lifecycle.mapProviderStatus(
                Objects.toString(orderData.get("order_status"), ""),
                Objects.toString(paymentData.get("payment_status"), ""));

        Map<String, Object> state = lifecycle.reconcile(orderId, canonical, rawBody, "WEBHOOK");

        if (!cfPaymentId.isBlank()) {
            db.update("UPDATE payment_transactions SET provider_payment_id=COALESCE(provider_payment_id,?), updated_at=now() " +
                    "WHERE order_id=? AND provider='CASHFREE'", cfPaymentId, orderId);
        }
        storePaymentEvent(orderId, cfOrderId, cfPaymentId, eventType, eventId, canonical, rawBody);

        Map<String, Object> response = new LinkedHashMap<>(state);
        response.put("received", true);
        return response;
    }

    /**
     * Create a new Cashfree session for payment retry.
     *
     * <p>Reuses the same local order. The provider order id is derived from the
     * order id, so the {@code payment_transactions} row is updated through its
     * unique index rather than duplicated, and no second order is created.
     */
    @Transactional
    public Map<String, Object> createRetrySession(UUID orderId, String returnUrl) {
        if (!cashfreeConfigured()) {
            System.err.println("[payment] Retry session aborted: Cashfree not configured");
            return Map.of("error", getCashfreeNotConfiguredMessage());
        }

        Map<String, Object> order = db.queryForMap(
                "SELECT order_number, total_amount, currency, customer_name, email, phone, payment_status " +
                    "FROM customer_orders WHERE id = ?", orderId);

        if (!lifecycle.isRetryEligible(Objects.toString(order.get("payment_status"), "PENDING"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order payment is not eligible for retry");
        }

        String cfOrderId = lifecycle.nextProviderOrderId(orderId);

        Map<String, Object> customerDetails = new LinkedHashMap<>();
        customerDetails.put("customer_id", orderId.toString());
        customerDetails.put("customer_name", Objects.toString(order.get("customer_name"), "Customer"));
        customerDetails.put("customer_email", Objects.toString(order.get("email"), ""));
        customerDetails.put("customer_phone", Objects.toString(order.get("phone"), "9999999999"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_id", cfOrderId);
        payload.put("order_amount", ((Number) order.get("total_amount")).doubleValue());
        payload.put("order_currency", order.get("currency"));
        payload.put("customer_details", customerDetails);

        String actualReturnUrl = returnUrl != null && !returnUrl.isBlank()
                ? returnUrl
                : frontendBaseUrl + paymentResultPath + "?orderId=" + orderId;
        payload.put("order_meta", Map.of("return_url", actualReturnUrl));

        Map<String, Object> response = http.post()
                .uri(cashfreeUrl + "/orders")
                .header("x-client-id", cashfreeAppId)
                .header("x-client-secret", cashfreeSecret)
                .header("x-api-version", cashfreeVersion)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(Map.class);

        db.update("INSERT INTO payment_transactions(id, order_id, provider, provider_order_id, amount, currency, status, provider_payload, created_at, updated_at) " +
                "VALUES(?, ?, 'CASHFREE', ?, ?, ?, 'CREATED', ?::jsonb, now(), now()) " +
                "ON CONFLICT(provider_order_id) DO UPDATE SET provider_payload = EXCLUDED.provider_payload, updated_at = now()",
                UUID.randomUUID(), orderId, cfOrderId, order.get("total_amount"), order.get("currency"),
                toJson(response));

        return response;
    }

    /**
     * Poll payment status from Cashfree and reconcile it.
     *
     * <p>Reads the status from the provider rather than trusting local state, then
     * hands it to the lifecycle service, so polling cannot mark an order paid that
     * Cashfree has not confirmed.
     */
    public Map<String, Object> pollPaymentStatus(UUID orderId) {
        if (!cashfreeConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, getCashfreeNotConfiguredMessage());
        }

        List<Map<String, Object>> rows = db.queryForList(
                "SELECT provider_order_id, status FROM payment_transactions " +
                "WHERE order_id = ? AND provider = 'CASHFREE' ORDER BY created_at DESC LIMIT 1",
                orderId);
        if (rows.isEmpty()) {
            return lifecycle.describe(orderId, "UNKNOWN", false, "POLL");
        }

        String cfOrderId = Objects.toString(rows.get(0).get("provider_order_id"), "");

        try {
            Map<String, Object> result = http.get()
                    .uri(cashfreeUrl + "/orders/" + cfOrderId)
                    .header("x-client-id", cashfreeAppId)
                    .header("x-client-secret", cashfreeSecret)
                    .header("x-api-version", cashfreeVersion)
                    .retrieve()
                    .body(Map.class);

            String canonical = lifecycle.mapProviderStatus(
                    Objects.toString(result.get("order_status"), ""),
                    Objects.toString(result.get("payment_status"), ""));
            return lifecycle.reconcile(orderId, canonical, toJson(result), "POLL");
        } catch (RestClientResponseException | ResourceAccessException e) {
            // The provider could not be reached. Report the state we already hold
            // rather than inventing one, so the customer is asked to try again.
            return lifecycle.describe(orderId, "UNKNOWN", false, "POLL");
        }
    }

    /** Verification entry point used by the payment return page. */
    public Map<String, Object> verifyOrder(UUID orderId) {
        return pollPaymentStatus(orderId);
    }

    private boolean eventAlreadyProcessed(String eventId) {
        try {
            Integer count = db.queryForObject("SELECT COUNT(*) FROM payment_events WHERE event_id = ?", Integer.class, eventId);
            return count != null && count > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void storePaymentEvent(UUID orderId, String providerOrderId, String providerPaymentId,
                                   String eventType, String eventId, String status, String rawPayload) {
        if (eventId == null || eventId.isBlank()) return;
        try {
            db.update("INSERT INTO payment_events(id, order_id, provider, provider_order_id, provider_payment_id, event_type, event_id, status, raw_payload, processed_at) " +
                    "VALUES(?, ?, 'CASHFREE', ?, ?, ?, ?, ?, ?::jsonb, now()) " +
                    "ON CONFLICT(event_id) DO NOTHING",
                    UUID.randomUUID(), orderId, providerOrderId, providerPaymentId,
                    eventType, eventId, status, rawPayload);
        } catch (RuntimeException e) {
            System.err.println("[payment] could not store event: " + e.getMessage());
        }
    }

    private boolean cashfreeConfigured() {
        return cashfreeAppId != null && !cashfreeAppId.isBlank()
                && cashfreeSecret != null && !cashfreeSecret.isBlank();
    }

    private String hmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> parseJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
