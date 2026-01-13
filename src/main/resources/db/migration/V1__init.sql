CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE account (
                         id UUID PRIMARY KEY,
                         user_id UUID NOT NULL,
                         balance NUMERIC(19,2) NOT NULL CHECK (balance >= 0),
                         base_currency VARCHAR(3) NOT NULL,
                         created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                         updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE payment (
                         id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                         account_id UUID NOT NULL REFERENCES account(id),
                         amount NUMERIC(19,2) NOT NULL,
                         currency VARCHAR(3) NOT NULL,
                         payment_id UUID NOT NULL,
                         status VARCHAR(20) NOT NULL,
                         created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                         CONSTRAINT uk_payment_idempotency UNIQUE (account_id, payment_id)
);

CREATE TABLE outbox_event (
                              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                              aggregate_type VARCHAR(50) NOT NULL,
                              aggregate_id UUID NOT NULL,
                              event_type VARCHAR(50) NOT NULL,
                              event_status VARCHAR(50) NOT NULL,
                              payload TEXT NOT NULL,
                              attempt_count INT NOT NULL DEFAULT 0,
                              last_error TEXT,
                              created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                              processed_at TIMESTAMP,
                              processing_started_at TIMESTAMP
);

-- Seed: 1-2 comptes
INSERT INTO account (id, user_id, balance, base_currency)
VALUES
    ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 1000.00, 'CHF'),
    ('33333333-3333-3333-3333-333333333333', '22222222-2222-2222-2222-222222222222', 50.00, 'CHF');