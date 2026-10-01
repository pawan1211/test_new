package com.fashion.commerce;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The single place where a payment outcome becomes a local order state.
 *
 * <h2>Why this exists</h2>
 * The payment status was previously written from three different places — the
 * verify endpoint, the webhook and a scheduled poll — and each of them understood
 * only the happy path. A failed, cancelled or expired payment therefore left the
 * order stuck reading "Placed · Pending" and, more seriously, left the stock hold
 * in place forever, because nothing ever released it. This class is now the only
 * writer, so a status cannot be interpreted two ways.
 *
 * <h2>Authority</h2>
 * The provider decides the payment status. The browser redirect decides nothing:
 * the return page calls the verify endpoint, which asks Cashfree and then hands the
 * answer here. A browser claiming success cannot mark an order paid, and a browser
 * claiming failure cannot undo a payment Cashfree has confirmed.
 *
 * <h2>Transitions</h2>
 * <pre>
 *   PENDING ──&gt; PAID      order CONFIRMED,        hold committed, bag lines removed
 *          ──&gt; FAILED    order PAYMENT_FAILED,    hold released
 *          ──&gt; CANCELLED order PAYMENT_CANCELLED, hold released
 *          ──&gt; EXPIRED  order PAYMENT_EXPIRED,   hold released
 *          ──&gt; PENDING  order PAYMENT_PENDING,   hold kept (it may still settle)
 * </pre>
 *
 * PAID and REFUNDED are final. A late duplicate event can never move a paid order
 * back to failed, which is what previously allowed a "FAILED then PAID" sequence
 * to release stock for a sale that had already completed. A payment Cashfree
 * confirms as PAID is always honoured, even if a stale local row said otherwise,
 * because the customer paid and the order must exist.
 *
 * <h2>Idempotency</h2>
 * Every statement is conditional on the state it is moving away from, and the
 * inventory work is delegated to {@link InventoryService}, which claims each hold
 * exactly once. Calling this method any number of times with the same provider
 * status produces the same database state, so duplicate webhooks, a refreshed
 * return page and a polled verification can all run freely.
 */
@Service
public class PaymentLifecycleService {

    /** Payment states that must never be downgraded. */
    private static final Set<String> SETTLED = Set.of("PAID", "REFUNDED");

    /** Payment states that mean the attempt is over and stock must come back. */
    private static final Set<String> TERMINAL_FAILURE = Set.of("FAILED", "CANCELLED", "EXPIRED");

    /** Order status that pairs with each terminal payment failure. */
    private static final Map<String, String> FAILURE_ORDER_STATUS = Map.of(
            "FAILED", "PAYMENT_FAILED",
            "CANCELLED", "PAYMENT_CANCELLED",
            "EXPIRED", "PAYMENT_EXPIRED");

    private final JdbcTemplate db;
    private final InventoryService inventory;

    public PaymentLifecycleService(JdbcTemplate db, InventoryService inventory) {
        this.db = db;
        this.inventory = inventory;
    }

    /* ------------------------------------------------------------ mapping */

    /**
     * Normalises a provider response into one of our canonical payment states.
     *
     * <p>Cashfree reports the order and the payment separately, and uses
     * {@code DROPPED} for a customer who abandoned the checkout. Both are folded in
     * deliberately rather than collapsing to UNKNOWN, because a dropped checkout
     * really is a failed attempt and must release its hold. Anything genuinely
     * unrecognised returns {@code UNKNOWN}, which never mutates local state.
     */
    public String mapProviderStatus(String orderStatus, String paymentStatus) {
        String order = upper(orderStatus);
        String payment = upper(paymentStatus);

        if (order.equals("PAID") || payment.equals("PAID") || payment.equals("SUCCESS")) {
            return "PAID";
        }
        if (order.equals("CANCELLED") || payment.equals("CANCELLED")) {
            return "CANCELLED";
        }
        if (order.equals("EXPIRED") || payment.equals("EXPIRED")) {
            return "EXPIRED";
        }
        if (order.equals("FAILED") || payment.equals("FAILED") || payment.equals("DROPPED")) {
            return "FAILED";
        }
        if (order.equals("ACTIVE") || order.equals("PENDING") || payment.equals("ACTIVE")) {
            return "PENDING";
        }
        return "UNKNOWN";
    }

    /* ------------------------------------------------------- reconciliation */

    /**
     * Applies a provider status to the local order.
     *
     * @param orderId        the local order
     * @param providerStatus already normalised by {@link #mapProviderStatus}
     * @param payload        the raw provider body, stored for audit only
     * @param source         where the status came from, e.g. VERIFY or WEBHOOK
     * @return the resulting order state, as returned to the browser
     */
    @Transactional
    public Map<String, Object> reconcile(UUID orderId, String providerStatus,
                                         String payload, String source) {
        Map<String, Object> order = loadForUpdate(orderId);
        String target = upper(providerStatus);
        String current = upper(Objects.toString(order.get("payment_status"), "PENDING"));

        // A settled order is final. Report it and change nothing: this is the rule
        // that stops a duplicate or late FAILED event from releasing the stock of
        // a sale that already completed.
        if (SETTLED.contains(current)) {
            return describe(orderId, target, false, source);
        }

        if ("PAID".equals(target)) {
            applyPaid(order, payload, source);
        } else if (TERMINAL_FAILURE.contains(target)) {
            applyFailure(order, target, payload, source);
        } else if ("PENDING".equals(target)) {
            applyPending(order, payload, source);
        }
        // UNKNOWN deliberately falls through: we could not read the provider's
        // answer, so the local state stays exactly as it was and the customer is
        // told to check again rather than being shown a guessed outcome.

        return describe(orderId, target, true, source);
    }

    private void applyPaid(Map<String, Object> order, String payload, String source) {
        UUID orderId = (UUID) order.get("id");

        db.update("UPDATE customer_orders SET payment_status='PAID', order_status='CONFIRMED', updated_at=now() " +
                "WHERE id=? AND payment_status <> 'PAID'", orderId);
        // payment_transactions.provider_payload is a text column, not jsonb, so the
        // payload is stored as the JSON string it already is and COALESCE resolves
        // text against text. Casting to jsonb here made every verification fail
        // with "COALESCE types jsonb and text cannot be matched".
        db.update("UPDATE payment_transactions SET status='PAID', provider_payload=COALESCE(?, provider_payload), updated_at=now() " +
                "WHERE order_id=? AND provider='CASHFREE' AND status <> 'PAID'", payload, orderId);

        /*
         * The hold may already be gone if an earlier event released it before the
         * payment actually settled. Cashfree confirming PAID is authoritative, so
         * the sale stands: take the stock back if it is still there, then commit.
         */
        if (!inventory.hasLiveReservation(orderId) && hasOrderLines(orderId)) {
            if (!inventory.reReserveForRetry(orderId)) {
                System.err.println("[payment] order " + orderId
                        + " was paid but its stock hold could not be restored; stock is short");
            }
        }
        inventory.commitReservation(orderId);
        clearPurchasedCartLines(orderId, order);

        recordEvent(orderId, source, "PAID", order, payload);
    }

    private void applyFailure(Map<String, Object> order, String target, String payload, String source) {
        UUID orderId = (UUID) order.get("id");
        String orderStatus = FAILURE_ORDER_STATUS.get(target);

        // Guarded on payment_status <> 'PAID' so a race between two events cannot
        // relabel a confirmed order as failed.
        db.update("UPDATE customer_orders SET payment_status=?, order_status=?, updated_at=now() " +
                "WHERE id=? AND payment_status NOT IN ('PAID','REFUNDED')", target, orderStatus, orderId);
        db.update("UPDATE payment_transactions SET status=?, provider_payload=COALESCE(?, provider_payload), updated_at=now() " +
                "WHERE order_id=? AND provider='CASHFREE' AND status <> 'PAID'", target, payload, orderId);

        int released = inventory.releaseReservation(orderId, "Payment " + target.toLowerCase(Locale.ROOT));
        if (released > 0) {
            System.out.println("[payment] released " + released + " hold(s) for order " + orderId
                    + " after " + target);
        }

        recordEvent(orderId, source, target, order, payload);
    }

    private void applyPending(Map<String, Object> order, String payload, String source) {
        UUID orderId = (UUID) order.get("id");

        // A pending signal must never resurrect an attempt that already failed:
        // that would re-hold stock the customer is no longer waiting on.
        db.update("UPDATE customer_orders SET payment_status='PENDING', order_status='PAYMENT_PENDING', updated_at=now() " +
                "WHERE id=? AND payment_status NOT IN ('PAID','REFUNDED','FAILED','CANCELLED','EXPIRED')", orderId);
        db.update("UPDATE payment_transactions SET status='PENDING', provider_payload=COALESCE(?, provider_payload), updated_at=now() " +
                "WHERE order_id=? AND provider='CASHFREE' AND status <> 'PAID'", payload, orderId);

        recordEvent(orderId, source, "PENDING", order, payload);
    }

    /* ---------------------------------------------------------- description */

    /**
     * Builds the order state the storefront renders.
     *
     * <p>This is the payload the payment return page and the account page both
     * read, so it always carries the same keys.
     */
    public Map<String, Object> describe(UUID orderId, String providerStatus, boolean changed, String source) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id, order_number, payment_status, order_status, total_amount, currency, created_at " +
                "FROM customer_orders WHERE id=?", orderId);
        if (rows.isEmpty()) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("found", false);
            missing.put("orderId", orderId.toString());
            missing.put("providerStatus", providerStatus);
            return missing;
        }

        Map<String, Object> row = rows.get(0);
        String paymentStatus = upper(Objects.toString(row.get("payment_status"), "PENDING"));
        String orderStatus = Objects.toString(row.get("order_status"), "PENDING_PAYMENT");
        // A provider that could not be read must not overwrite what we already
        // know locally, otherwise a transient outage would show a paid order as
        // "unknown" to the customer.
        String reported = "UNKNOWN".equals(providerStatus) ? paymentStatus : providerStatus;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", true);
        out.put("orderId", orderId.toString());
        out.put("orderNumber", Objects.toString(row.get("order_number"), ""));
        out.put("verified", "PAID".equals(paymentStatus));
        out.put("paymentStatus", paymentStatus);
        out.put("providerStatus", reported);
        out.put("orderStatus", orderStatus);
        out.put("amount", row.get("total_amount"));
        out.put("currency", Objects.toString(row.get("currency"), "INR"));
        out.put("createdAt", row.get("created_at"));
        out.put("stockCommitted", "PAID".equals(paymentStatus));
        out.put("reservationReleased", TERMINAL_FAILURE.contains(paymentStatus));
        out.put("retryEligible", isRetryEligible(paymentStatus));
        out.put("source", source);
        out.put("changed", changed);
        return out;
    }

    /**
     * A payment can be attempted again only while it has genuinely not completed.
     * A settled or already-failed order is not retryable through this path; a
     * settled one is a support case, and retrying a cancelled order goes through a
     * fresh checkout so the customer can change what they are buying.
     */
    public boolean isRetryEligible(String paymentStatus) {
        String status = upper(paymentStatus);
        return "PENDING".equals(status) || "FAILED".equals(status)
                || "CANCELLED".equals(status) || "EXPIRED".equals(status);
    }

    /**
     * The provider order id for the next payment attempt on a local order.
     *
     * <p>Cashfree rejects a second order that reuses an existing {@code order_id},
     * so a retry cannot simply repeat the first identifier. Each attempt gets its
     * own suffix — {@code luxe_<hex>}, then {@code luxe_<hex>_a2} — while still
     * being derived from the one local order. The local order is never duplicated;
     * only the provider-side attempt is, which is what a second genuine attempt to
     * pay for the same basket actually requires.
     *
     * <p>Verification and the webhook resolve an order through its transaction
     * rows and prefer the most recent, so the newest attempt is always the live one.
     */
    public String nextProviderOrderId(UUID orderId) {
        String base = "luxe_" + orderId.toString().replace("-", "");
        Integer attempts;
        try {
            attempts = db.queryForObject(
                    "SELECT COUNT(*) FROM payment_transactions WHERE order_id=? AND provider='CASHFREE'",
                    Integer.class, orderId);
        } catch (RuntimeException e) {
            attempts = 0;
        }
        int next = (attempts == null ? 0 : attempts) + 1;
        return next <= 1 ? base : base + "_a" + next;
    }

    /* -------------------------------------------------------------- helpers */

    /**
     * Reads the order for update so two simultaneous verifications of the same
     * payment serialise instead of both deciding the transition.
     */
    private Map<String, Object> loadForUpdate(UUID orderId) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id, order_number, payment_status, order_status, total_amount, currency, storefront_session_id " +
                "FROM customer_orders WHERE id=? FOR UPDATE", orderId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Order not found: " + orderId);
        }
        return rows.get(0);
    }

    private boolean hasOrderLines(UUID orderId) {
        Integer count = db.queryForObject(
                "SELECT COUNT(*) FROM order_items WHERE order_id=?", Integer.class, orderId);
        return count != null && count > 0;
    }

    /**
     * Removes only the bag lines this order covered.
     *
     * <p>Checkout turns the whole bag into the order, so every line in the bag that
     * belongs to this session is covered by it. Deleting by variant rather than
     * clearing the session means a line added in another tab after checkout is left
     * alone instead of being silently thrown away with a successful purchase.
     */
    private void clearPurchasedCartLines(UUID orderId, Map<String, Object> order) {
        Object session = order.get("storefront_session_id");
        if (session == null) {
            return;
        }
        try {
            db.update("DELETE FROM storefront_cart_items ci WHERE ci.session_id=? AND ci.variant_id IN " +
                    "(SELECT variant_id FROM order_items WHERE order_id=? AND variant_id IS NOT NULL)",
                    session, orderId);
        } catch (RuntimeException e) {
            // The order is paid and the stock is committed. A failure to tidy the
            // bag must never reverse or fail the payment.
            System.err.println("[payment] could not clear bag for order " + orderId + ": " + e.getMessage());
        }
    }

    /**
     * Appends to the payment audit trail.
     *
     * <p>Event rows are keyed by a generated id rather than the provider's, because
     * verification deliberately records every observation: the history of "what
     * did we believe, and when" is what makes a disputed payment diagnosable. The
     * provider's own event id is kept when present so provider-side deduplication
     * still works.
     */
    private void recordEvent(UUID orderId, String source, String status,
                             Map<String, Object> order, String payload) {
        try {
            db.update("INSERT INTO payment_events(id, order_id, provider, provider_order_id, provider_payment_id, " +
                    "event_type, event_id, status, amount, currency, raw_payload, processed_at) " +
                    "VALUES(?,?,'CASHFREE',?,?,?,?,?,?,?,COALESCE(?::jsonb,'{}'::jsonb),now()) " +
                    "ON CONFLICT(event_id) DO NOTHING",
                    UUID.randomUUID(), orderId,
                    providerOrderId(orderId),
                    providerPaymentId(orderId),
                    source,
                    UUID.randomUUID().toString(),
                    status,
                    order.get("total_amount"),
                    Objects.toString(order.get("currency"), "INR"),
                    payload);
        } catch (RuntimeException e) {
            System.err.println("[payment] could not record event for order " + orderId + ": " + e.getMessage());
        }
    }

    private String providerOrderId(UUID orderId) {
        return scalar("SELECT provider_order_id FROM payment_transactions WHERE order_id=? AND provider='CASHFREE' " +
                "ORDER BY created_at DESC LIMIT 1", orderId);
    }

    private String providerPaymentId(UUID orderId) {
        return scalar("SELECT provider_payment_id FROM payment_transactions WHERE order_id=? AND provider='CASHFREE' " +
                "ORDER BY created_at DESC LIMIT 1", orderId);
    }

    private String scalar(String sql, Object... args) {
        try {
            List<String> rows = db.queryForList(sql, String.class, args);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
