ALTER TABLE payment_transactions
ADD COLUMN IF NOT EXISTS provider_payload JSONB;
