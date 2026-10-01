-- Payment lifecycle states.
--
-- The payment code already wanted to record CANCELLED/EXPIRED, but both the
-- order and the transaction CHECK constraints only allowed the older set, so a
-- cancelled or expired Cashfree payment raised a 23514 check violation and the
-- webhook returned 500. Cashfree retries those events, which is exactly how one
-- bad state value turns into duplicate deliveries.
--
-- This migration widens both constraints. It only adds values, so existing rows
-- and any code still writing the original set keep working unchanged.

ALTER TABLE customer_orders DROP CONSTRAINT IF EXISTS customer_orders_payment_status_check;
ALTER TABLE customer_orders
    ADD CONSTRAINT customer_orders_payment_status_check
    CHECK (payment_status IN ('PENDING', 'AUTHORIZED', 'PAID', 'FAILED', 'CANCELLED', 'EXPIRED', 'REFUNDED'));

ALTER TABLE payment_transactions DROP CONSTRAINT IF EXISTS payment_transactions_status_check;
ALTER TABLE payment_transactions
    ADD CONSTRAINT payment_transactions_status_check
    CHECK (status IN ('CREATED', 'AUTHORIZED', 'CAPTURED', 'PAID', 'FAILED', 'CANCELLED', 'EXPIRED', 'REFUNDED'));

-- order_status already contains PENDING_PAYMENT, which is the honest state for
-- an order that exists but has not been paid yet. Until now checkout inserted
-- PLACED alongside payment_status = PENDING, so a failed or abandoned payment
-- rendered as "Placed - Pending" and looked like a successful order.
--
-- Move only the unpaid orders still sitting in that ambiguous state. Paid and
-- failed orders are left untouched so historical records stay accurate.
UPDATE customer_orders
SET order_status = 'PENDING_PAYMENT'
WHERE order_status = 'PLACED'
  AND payment_status IN ('PENDING', 'FAILED', 'CANCELLED', 'EXPIRED');

-- The per-provider order id is the idempotency key for retry sessions and for
-- locating an order from a webhook. Confirm it is unique before relying on it.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_transactions_provider_order_id
    ON payment_transactions (provider, provider_order_id)
    WHERE provider_order_id IS NOT NULL;

-- Order history is filtered by calendar year on the account page; without this
-- the year filter degrades into a sequential scan of every order a customer has
-- ever placed.
CREATE INDEX IF NOT EXISTS idx_customer_orders_user_created
    ON customer_orders (user_id, created_at DESC);
