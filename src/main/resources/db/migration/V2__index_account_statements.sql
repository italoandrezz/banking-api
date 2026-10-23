CREATE INDEX idx_transactions_origin_statement
    ON transactions (account_id, created_at DESC, id DESC);

CREATE INDEX idx_transactions_destination_statement
    ON transactions (destination_account_id, created_at DESC, id DESC)
    WHERE destination_account_id IS NOT NULL;
