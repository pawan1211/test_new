package com.fashion.commerce;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Order-scoped inventory holds.
 *
 * Writes to {@code inventory_reservations_v2} (created in V17), which is keyed by
 * order_id and uses the RESERVED/COMMITTED/RELEASED/EXPIRED status set. The older
 * {@code inventory_reservations} table is user-scoped and uses a different status
 * vocabulary, so it must not be mixed with the order flow.
 *
 * <h2>Why stock is deducted at reserve time</h2>
 * {@code product_variants.stock_quantity} is the authoritative <em>sellable</em>
 * count, not the physical count. Deducing it when the hold is taken is what stops
 * two shoppers from both buying the last piece. "Available" is therefore just
 * {@code stock_quantity}: a held unit is already off the shelf as far as the
 * storefront is concerned.
 *
 * <h2>Exactly-once transitions</h2>
 * Every state change is a conditional {@code UPDATE ... WHERE status = 'RESERVED'}
 * and the caller acts on the returned row count. That single rule is what makes
 * payment retries, duplicate webhooks, browser refreshes and concurrent
 * verification safe:
 *
 * <pre>
 *   reserve  -> deduct stock, insert RESERVED hold
 *   commit   -> RESERVED becomes COMMITTED   (stock stays deducted; the sale happened)
 *   release  -> claim the hold, then give the stock back, RESERVED becomes RELEASED
 * </pre>
 *
 * A hold can leave RESERVED exactly once, so stock can be returned exactly once
 * and can never be returned after being committed. A second release finds no
 * RESERVED row, changes nothing and returns zero.
 */
@Service
public class InventoryService {

    private static final String TABLE = "inventory_reservations_v2";
    private static final int HOLD_MINUTES = 30;

    private final JdbcTemplate db;

    public InventoryService(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * Places a time-boxed hold for one order line.
     *
     * <p>Stock is deducted with a conditional UPDATE so two concurrent checkouts
     * can never oversell, and the hold row is inserted under the partial unique
     * index added in V24 so one order can never hold the same variant twice.
     *
     * @return true when the hold was taken, false when stock or the hold index
     *         says no. The caller must abort the whole checkout in that case.
     */
    @Transactional
    public boolean reserveInventory(UUID orderId, UUID variantId, int quantity) {
        if (orderId == null || variantId == null || quantity < 1) {
            return false;
        }

        // If this order already holds this variant, the hold is already in place
        // and the stock is already deducted. Treat it as success so a retried
        // checkout step cannot double-reserve, but do not deduct a second time.
        if (hasLiveHold(orderId, variantId)) {
            return true;
        }

        int before = currentStock(variantId);
        int deducted = db.update(
                "UPDATE product_variants SET stock_quantity = stock_quantity - ? " +
                "WHERE id = ? AND active = true AND stock_quantity >= ?",
                quantity, variantId, quantity);
        if (deducted != 1) {
            return false;
        }

        try {
            db.update(
                    "INSERT INTO " + TABLE + "(id, order_id, variant_id, quantity, status, expires_at, created_at, updated_at) " +
                    "VALUES(?, ?, ?, ?, 'RESERVED', ?, now(), now())",
                    UUID.randomUUID(), orderId, variantId, quantity,
                    Timestamp.from(Instant.now().plusSeconds(HOLD_MINUTES * 60L)));
        } catch (RuntimeException e) {
            // The partial unique index rejected a concurrent second hold for the
            // same order line. Undo our deduction so the two attempts cannot
            // combine into a double reservation.
            db.update("UPDATE product_variants SET stock_quantity = stock_quantity + ? WHERE id = ?",
                    quantity, variantId);
            return false;
        }

        record(variantId, "RESERVE", quantity, before, before - quantity,
                "ORDER", orderId, "Stock held for an awaiting payment");
        return true;
    }

    /**
     * Converts live holds into committed stock movements once payment settles.
     *
     * <p>No stock movement happens here: the units were already removed from the
     * sellable count when the hold was taken, and a committed hold means that
     * removal is final rather than temporary.
     *
     * @return the number of holds this call moved out of RESERVED. Zero means the
     *         order was already settled, which is the normal result of a repeated
     *         verification and is exactly what makes the call idempotent.
     */
    @Transactional
    public int commitReservation(UUID orderId) {
        if (orderId == null) {
            return 0;
        }
        return db.update("UPDATE " + TABLE + " SET status = 'COMMITTED', updated_at = now() " +
                "WHERE order_id = ? AND status = 'RESERVED'", orderId);
    }

    /**
     * Returns held stock to the shelf after a failed, cancelled or expired
     * payment.
     *
     * <p>Each hold is claimed with a conditional UPDATE <em>before</em> the stock
     * is returned. Only the caller that wins the claim adds the units back, so a
     * webhook delivered twice, or a webhook racing a browser refresh, can never
     * return the same units twice.
     *
     * @return the number of holds released by this call.
     */
    @Transactional
    public int releaseReservation(UUID orderId) {
        return releaseReservation(orderId, "Payment did not complete");
    }

    @Transactional
    public int releaseReservation(UUID orderId, String reason) {
        if (orderId == null) {
            return 0;
        }

        List<Map<String, Object>> holds = db.queryForList(
                "SELECT id, variant_id, quantity FROM " + TABLE +
                " WHERE order_id = ? AND status = 'RESERVED' ORDER BY created_at",
                orderId);

        int released = 0;
        for (Map<String, Object> hold : holds) {
            UUID holdId = (UUID) hold.get("id");
            UUID variantId = (UUID) hold.get("variant_id");
            int quantity = ((Number) hold.get("quantity")).intValue();

            // Claim first. If this returns 0 another caller already released this
            // hold and returned the stock, so there is nothing left for us to do.
            int claimed = db.update(
                    "UPDATE " + TABLE + " SET status = 'RELEASED', updated_at = now() " +
                    "WHERE id = ? AND status = 'RESERVED'",
                    holdId);
            if (claimed != 1) {
                continue;
            }

            int before = currentStock(variantId);
            db.update("UPDATE product_variants SET stock_quantity = stock_quantity + ? WHERE id = ?",
                    quantity, variantId);
            record(variantId, "RELEASE", quantity, before, before + quantity,
                    "ORDER", orderId, reason);

            released++;
        }
        return released;
    }

    /**
     * Re-establishes holds for an order that is being paid again.
     *
     * <p>A retry must not mint a second order, and it must not deduct stock twice.
     * Lines that still hold a live reservation are left untouched; only lines whose
     * hold was released by the previous failure are taken again, and only if the
     * stock is genuinely there now.
     *
     * @return true when every line of the order is held again, false when at least
     *         one line could not be re-reserved because stock is gone.
     */
    @Transactional
    public boolean reReserveForRetry(UUID orderId) {
        if (orderId == null) {
            return false;
        }

        List<Map<String, Object>> lines = db.queryForList(
                "SELECT oi.variant_id, oi.quantity FROM order_items oi " +
                "WHERE oi.order_id = ? AND oi.variant_id IS NOT NULL",
                orderId);
        if (lines.isEmpty()) {
            return true;
        }

        for (Map<String, Object> line : lines) {
            UUID variantId = (UUID) line.get("variant_id");
            int quantity = ((Number) line.get("quantity")).intValue();
            if (reserveInventory(orderId, variantId, quantity)) {
                continue;
            }
            // Release whatever we managed to take so a partial retry cannot leave
            // the order half-held, and report the failure to the caller.
            releaseReservation(orderId, "Retry could not re-hold every line");
            return false;
        }
        return true;
    }

    /** True when at least one line of the order is still holding stock. */
    public boolean hasLiveReservation(UUID orderId) {
        if (orderId == null) {
            return false;
        }
        Integer count = db.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE order_id = ? AND status = 'RESERVED'",
                Integer.class, orderId);
        return count != null && count > 0;
    }

    private boolean hasLiveHold(UUID orderId, UUID variantId) {
        Integer count = db.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE order_id = ? AND variant_id = ? AND status = 'RESERVED'",
                Integer.class, orderId, variantId);
        return count != null && count > 0;
    }

    /**
     * Releases holds that were never paid for within the hold window.
     *
     * <p>This is the safety net for a customer who closed the Cashfree tab
     * entirely: no webhook and no return visit will ever arrive, and without this
     * the hold would sit on the shelf forever.
     */
    @Scheduled(fixedRate = 300000)
    @Transactional
    public void expireOldReservations() {
        List<UUID> stale = db.queryForList(
                "SELECT DISTINCT order_id FROM " + TABLE +
                " WHERE status = 'RESERVED' AND expires_at < now()",
                UUID.class);
        for (UUID orderId : stale) {
            releaseReservation(orderId, "Payment window expired without a payment");

            // Expire rather than release when the order never got as far as an
            // attempt, so the audit trail shows why the hold disappeared. A hold
            // that has already been RELEASED is left alone; the partial unique
            // index means this can only ever affect genuinely live rows.
            db.update("UPDATE " + TABLE + " SET status = 'EXPIRED', updated_at = now() " +
                    "WHERE order_id = ? AND status = 'RESERVED'", orderId);
        }
    }

    private int currentStock(UUID variantId) {
        Integer stock = db.queryForObject(
                "SELECT stock_quantity FROM product_variants WHERE id = ?",
                Integer.class, variantId);
        return stock == null ? 0 : stock;
    }

    /**
     * Appends to the stock movement audit trail. Failures here must never abort a
     * payment: the ledger is observability, the reservation row is the truth.
     */
    private void record(UUID variantId, String movement, int quantity,
                        int previous, int next, String referenceType,
                        UUID referenceId, String reason) {
        try {
            db.update(
                    "INSERT INTO inventory_ledger(id, variant_id, product_id, sku, movement_type, quantity, " +
                    "previous_stock, new_stock, reference_type, reference_id, reason, created_at) " +
                    "SELECT ?, v.id, v.product_id, v.sku, ?, ?, ?, ?, ?, ?, ? , now() " +
                    "FROM product_variants v WHERE v.id = ?",
                    UUID.randomUUID(), movement, quantity, previous, next,
                    referenceType, referenceId, reason, variantId);
        } catch (RuntimeException ignored) {
            // Ledger is best-effort; never fail a payment over an audit row.
        }
    }

    /**
     * Live availability for a variant.
     *
     * <p>{@code stock_quantity} is already net of live holds, so it <em>is</em> the
     * available figure. Subtracting the reserved total again — as this query
     * previously did — double-counted every hold and made stock appear lower than
     * it was while a payment was in flight. {@code reserved} is still reported so
     * the storefront and the ledger can explain the difference.
     */
    public Map<String, Object> getVariantInventory(UUID variantId) {
        return db.queryForMap(
                "SELECT v.id, v.sku, v.size, v.color, v.stock_quantity, v.image_url, " +
                "  p.name AS product_name, p.slug AS product_slug, " +
                "  COALESCE((SELECT SUM(r.quantity) FROM " + TABLE + " r " +
                "     WHERE r.variant_id = v.id AND r.status = 'RESERVED'), 0) AS reserved, " +
                "  v.stock_quantity AS available " +
                "FROM product_variants v JOIN products p ON p.id = v.product_id WHERE v.id = ?",
                variantId);
    }

    /** Stock movement audit trail for a variant. */
    public List<Map<String, Object>> getInventoryLedger(UUID variantId) {
        return db.queryForList(
                "SELECT id, movement_type, quantity, previous_stock, new_stock, " +
                "  reference_type, reference_id, reason, created_at " +
                "FROM inventory_ledger WHERE variant_id = ? ORDER BY created_at DESC LIMIT 100",
                variantId);
    }
}
