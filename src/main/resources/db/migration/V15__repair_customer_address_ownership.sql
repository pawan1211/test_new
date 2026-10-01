-- Customer sign-in uses customer_users; V3 accidentally tied addresses to app_users.
-- Keep any rows that cannot be mapped in an archive before repairing ownership.
CREATE TABLE IF NOT EXISTS customer_address_legacy_archive (
  id UUID PRIMARY KEY,
  legacy_user_id UUID NOT NULL,
  recipient_name VARCHAR(160) NOT NULL,
  phone VARCHAR(30) NOT NULL,
  address_line1 VARCHAR(250) NOT NULL,
  address_line2 VARCHAR(250),
  city VARCHAR(120) NOT NULL,
  state VARCHAR(120) NOT NULL,
  postal_code VARCHAR(20) NOT NULL,
  country VARCHAR(2) NOT NULL,
  is_default BOOLEAN NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  archive_reason TEXT NOT NULL,
  archived_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO customer_address_legacy_archive
  (id, legacy_user_id, recipient_name, phone, address_line1, address_line2,
   city, state, postal_code, country, is_default, created_at, archive_reason)
SELECT a.id, a.user_id, a.recipient_name, a.phone, a.address_line1, a.address_line2,
       a.city, a.state, a.postal_code, a.country, a.is_default, a.created_at,
       'No matching customer account could be found during ownership repair'
FROM customer_addresses a
WHERE NOT EXISTS (SELECT 1 FROM customer_users c WHERE c.id = a.user_id)
  AND NOT EXISTS (
    SELECT 1 FROM app_users old_user
    JOIN customer_users c ON lower(trim(c.email)) = lower(trim(old_user.email))
    WHERE old_user.id = a.user_id
  )
ON CONFLICT (id) DO NOTHING;

UPDATE customer_addresses a
SET is_default = FALSE
WHERE a.is_default = TRUE;

UPDATE customer_addresses a
SET user_id = c.id
FROM app_users old_user
JOIN customer_users c ON lower(trim(c.email)) = lower(trim(old_user.email))
WHERE a.user_id = old_user.id
  AND NOT EXISTS (SELECT 1 FROM customer_users direct_user WHERE direct_user.id = a.user_id);

DELETE FROM customer_addresses a
WHERE NOT EXISTS (SELECT 1 FROM customer_users c WHERE c.id = a.user_id);

ALTER TABLE customer_addresses DROP CONSTRAINT IF EXISTS customer_addresses_user_id_fkey;
ALTER TABLE customer_addresses
  ADD CONSTRAINT customer_addresses_user_id_fkey
  FOREIGN KEY (user_id) REFERENCES customer_users(id) ON DELETE CASCADE;

ALTER TABLE customer_addresses
  ADD COLUMN IF NOT EXISTS address_type VARCHAR(20) NOT NULL DEFAULT 'HOME';
ALTER TABLE customer_addresses
  DROP CONSTRAINT IF EXISTS customer_addresses_address_type_check;
ALTER TABLE customer_addresses
  ADD CONSTRAINT customer_addresses_address_type_check
  CHECK (address_type IN ('HOME', 'WORK', 'OTHER'));

-- Merge exact duplicates per customer while preserving the older rows in the archive.
WITH ranked AS (
  SELECT id, row_number() OVER (
    PARTITION BY user_id, lower(btrim(recipient_name)), lower(btrim(phone)),
      lower(btrim(address_line1)), lower(btrim(coalesce(address_line2, ''))),
      lower(btrim(city)), lower(btrim(state)), upper(btrim(postal_code)), upper(country)
    ORDER BY created_at DESC, id
  ) AS position
  FROM customer_addresses
), duplicates AS (
  SELECT a.* FROM customer_addresses a JOIN ranked r USING (id) WHERE r.position > 1
)
INSERT INTO customer_address_legacy_archive
  (id, legacy_user_id, recipient_name, phone, address_line1, address_line2,
   city, state, postal_code, country, is_default, created_at, archive_reason)
SELECT id, user_id, recipient_name, phone, address_line1, address_line2,
       city, state, postal_code, country, is_default, created_at,
       'Duplicate address merged into the most recently saved copy'
FROM duplicates
ON CONFLICT (id) DO NOTHING;

WITH ranked AS (
  SELECT id, row_number() OVER (
    PARTITION BY user_id, lower(btrim(recipient_name)), lower(btrim(phone)),
      lower(btrim(address_line1)), lower(btrim(coalesce(address_line2, ''))),
      lower(btrim(city)), lower(btrim(state)), upper(btrim(postal_code)), upper(country)
    ORDER BY created_at DESC, id
  ) AS position
  FROM customer_addresses
)
DELETE FROM customer_addresses a USING ranked r WHERE a.id = r.id AND r.position > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_address_identity
  ON customer_addresses (
    user_id, lower(btrim(recipient_name)), lower(btrim(phone)),
    lower(btrim(address_line1)), lower(btrim(coalesce(address_line2, ''))),
    lower(btrim(city)), lower(btrim(state)), upper(btrim(postal_code)), upper(country)
  );

WITH ranked AS (
  SELECT id, row_number() OVER (PARTITION BY user_id ORDER BY created_at DESC, id) AS position
  FROM customer_addresses
)
UPDATE customer_addresses a SET is_default = TRUE
FROM ranked r WHERE a.id = r.id AND r.position = 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_default_address
  ON customer_addresses(user_id) WHERE is_default = TRUE;

-- Orders also belong to the authenticated customer_users identity. Keep orders
-- intact and map the legacy app_users identity by email where possible.
UPDATE customer_orders o
SET user_id = c.id
FROM app_users old_user
JOIN customer_users c ON lower(trim(c.email)) = lower(trim(old_user.email))
WHERE o.user_id = old_user.id
  AND NOT EXISTS (SELECT 1 FROM customer_users direct_user WHERE direct_user.id = o.user_id);

UPDATE customer_orders o
SET user_id = NULL
WHERE o.user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM customer_users c WHERE c.id = o.user_id);

ALTER TABLE customer_orders DROP CONSTRAINT IF EXISTS customer_orders_user_id_fkey;
ALTER TABLE customer_orders
  ADD CONSTRAINT customer_orders_user_id_fkey
  FOREIGN KEY (user_id) REFERENCES customer_users(id) ON DELETE SET NULL;
