-- Retained independently of mutable account/customer records for replay history.
CREATE TABLE financial_idempotency (
    customer_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint TEXT NOT NULL,
    response_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (customer_id, idempotency_key)
);
