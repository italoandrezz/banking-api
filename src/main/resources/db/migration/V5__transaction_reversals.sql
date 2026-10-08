ALTER TABLE transactions
    ADD COLUMN original_transaction_id UUID REFERENCES transactions(id),
    ADD COLUMN reversal_admin_id UUID REFERENCES customers(id),
    ADD COLUMN reversal_reason VARCHAR(255),
    ADD CONSTRAINT uq_transaction_reversal UNIQUE (original_transaction_id),
    ADD CONSTRAINT chk_reversal_metadata CHECK (
        (original_transaction_id IS NULL AND reversal_admin_id IS NULL AND reversal_reason IS NULL)
        OR
        (original_transaction_id IS NOT NULL AND original_transaction_id <> id
         AND reversal_admin_id IS NOT NULL AND reversal_reason IS NOT NULL
         AND length(btrim(reversal_reason)) > 0)
    );
