-- V25: Account-scoped bag and wishlist.
--
-- THE BUG THIS FIXES
--
-- The bag lived in storefront_cart_items, keyed by storefront_sessions.id, and
-- that session id was a UUID the browser generated and kept in localStorage. The
-- wishlist was never persisted at all — it was a localStorage array. Neither was
-- connected to an account. So the "bag" and "wishlist" a customer saw belonged to
-- the *browser*, not to them:
--
--   User A signs in, fills a bag and a wishlist, signs out.
--   User B signs in on the same browser.
--   User B sees User A's bag and wishlist.
--
-- The only thing that hid this in practice was sign-out clearing localStorage, so
-- the two accounts never really coexisted. That is a cosmetic guard, not
-- ownership: it also destroys the signed-in customer's own bag every time they
-- sign out, and it does nothing on a shared or public machine where a previous
-- session was left open.
--
-- WHAT CHANGES
--
-- storefront_sessions gains a nullable customer_id. A signed-in customer's bag is
-- now resolved by *who they are*, so it is the same bag on every device they sign
-- in from, and a different bag for every other customer. The session id remains
-- the guest mechanism and continues to work exactly as before for anyone not
-- signed in.
--
-- A wishlist table is added, because there was nothing durable to own. It is
-- keyed by (customer_id, product_id) so the same product cannot be saved twice,
-- and cascade-deletes with the account.
--
-- Both are additive. No rows are deleted or rewritten, the guest path is untouched,
-- and the V3 customer_carts / wishlists tables are left alone: they reference
-- app_users, which is not the table authentication actually uses (that is
-- customer_users), so they cannot be the store of record for a signed-in
-- customer and are not relied on here.

ALTER TABLE storefront_sessions ADD COLUMN IF NOT EXISTS customer_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'storefront_sessions_customer_fk'
    ) THEN
        ALTER TABLE storefront_sessions
            ADD CONSTRAINT storefront_sessions_customer_fk
            FOREIGN KEY (customer_id)
            REFERENCES customer_users (id)
            ON DELETE CASCADE;
    END IF;
END
$$;

-- One bag row per customer. The unique index is what makes "resolve my bag by who
-- I am" a single, race-free lookup rather than a select-then-insert that two
-- concurrent tabs could both win.
CREATE UNIQUE INDEX IF NOT EXISTS uq_storefront_sessions_customer
    ON storefront_sessions (customer_id)
    WHERE customer_id IS NOT NULL;

-- Wishlist, owned by the customer.
CREATE TABLE IF NOT EXISTS customer_wishlist (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES customer_users (id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (customer_id, product_id)
);

-- The wishlist is read in one shot per account, newest first.
CREATE INDEX IF NOT EXISTS idx_customer_wishlist_customer
    ON customer_wishlist (customer_id, created_at DESC);