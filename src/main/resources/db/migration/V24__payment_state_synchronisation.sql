-- V24: Payment state synchronisation, order-history shape and reservation safety.
--
-- This migration exists because four separate defects in the payment flow all
-- needed schema support that no earlier migration provided.
--
-- 1. ORDER STATUS COULD NOT EXPRESS AN UNPAID OUTCOME
--    customer_orders.order_status only allowed
--    (PENDING_PAYMENT, PLACED, CONFIRMED, PROCESSING, SHIPPED, DELIVERED,
--    CANCELLED, REFUNDED).
--    There was no value that could describe "this checkout was attempted and the
--    payment did not succeed". FAILED / CANCELLED / EXPIRED all had to be
--    rendered as PLACED, which is indistinguishable from an order that is simply
--    awaiting payment. The values below give each terminal provider outcome its
--    own order status so order history is honest without inferring anything.
--
-- 2. payment_transactions HAD NO PENDING VALUE
--    A Cashfree session that is created but not yet settled is ACTIVE at the
--    provider. Recording that as CREATED forever meant a pending payment could
--    never be represented, so the reconciliation loop had nothing to write.
--
-- 3. THE ORDER COULD NOT REACH BACK TO THE BAG IT CAME FROM
--    storefront_cart_items is keyed by storefront_sessions.id. Without a link
--    from the order to that session there is no way to clear exactly the bag
--    lines that were purchased once payment settles, while leaving unrelated
--    lines alone. storefront_session_id is nullable so every existing order stays
--    valid and simply has no bag to reconcile.
--
-- 4. NOTHING STOPPED A SECOND LIVE HOLD FOR THE SAME ORDER LINE
--    A retry creates a fresh payment session. Without a uniqueness rule on live
--    holds, a retried order could take a second RESERVED hold for the same
--    variant and double-deduct stock for one order. The partial unique index
--    below makes the hold the serialisation point: the second attempt is rejected
--    by the database, not by a read-then-write race in Java.
--
-- Nothing here deletes or rewrites existing rows. The order_status UPDATE only
-- relabels orders that are demonstrably unpaid, so a PAID or already-failed
-- order keeps the status it earned.

-- ---------------------------------------------------------------- 1 + 2
-- Only ever widens the accepted value set; every previously legal value stays
-- legal, so code that has not been updated yet cannot start failing.
ALTER TABLE customer_orders DROP CONSTRAINT IF EXISTS customer_orders_order_status_check;
ALTER TABLE customer_orders
    ADD CONSTRAINT customer_orders_order_status_check
    CHECK (order_status IN (
        'PENDING_PAYMENT',
        'PLACED',
        'CONFIRMED',
        'PROCESSING',
        'SHIPPED',
        'DELIVERED',
        'CANCELLED',
        'REFUNDED',
        'PAYMENT_PENDING',
        'PAYMENT_FAILED',
        'PAYMENT_CANCELLED',
        'PAYMENT_EXPIRED'
    ));

ALTER TABLE payment_transactions DROP CONSTRAINT IF EXISTS payment_transactions_status_check;
ALTER TABLE payment_transactions
    ADD CONSTRAINT payment_transactions_status_check
    CHECK (status IN (
        'CREATED',
        'PENDING',
        'AUTHORIZED',
        'CAPTURED',
        'PAID',
        'FAILED',
        'CANCELLED',
        'EXPIRED',
        'REFUNDED'
    ));

-- Backfill only what is provably an unsettled attempt. PAID is explicitly
-- excluded so a confirmed order is never relabelled, and an order that already
-- carries a payment-specific status is left alone.
UPDATE customer_orders
SET order_status = 'PAYMENT_PENDING',
    updated_at = now()
WHERE order_status IN ('PENDING_PAYMENT', 'PLACED')
  AND payment_status = 'PENDING';

-- ---------------------------------------------------------------- 3
-- Lets the settlement step remove exactly the purchased lines from the bag that
-- produced the order. Nullable: orders placed before this migration, and orders
-- placed by any non-storefront channel, simply have nothing to clear.
ALTER TABLE customer_orders ADD COLUMN IF NOT EXISTS storefront_session_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'customer_orders_storefront_session_fk'
    ) THEN
        ALTER TABLE customer_orders
            ADD CONSTRAINT customer_orders_storefront_session_fk
            FOREIGN KEY (storefront_session_id)
            REFERENCES storefront_sessions (id)
            ON DELETE SET NULL;
    END IF;
END
$$;

-- ---------------------------------------------------------------- 4
-- One live hold per order line. COMMITTED / RELEASED / EXPIRED rows are kept
-- forever as the audit trail, so the index is partial: only a RESERVED row
-- blocks a second hold.
CREATE UNIQUE INDEX IF NOT EXISTS uq_inventory_reservations_v2_live_hold
    ON inventory_reservations_v2 (order_id, variant_id)
    WHERE status = 'RESERVED';

-- Order history renders newest-first for one customer on every page load; V23
-- added the (user_id, created_at DESC) index, and this composite lets the
-- account page filter by calendar year without a sequential scan.
CREATE INDEX IF NOT EXISTS idx_customer_orders_user_status_created
    ON customer_orders (user_id, order_status, created_at DESC);
