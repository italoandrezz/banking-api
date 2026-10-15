-- Explicit allowlist: never grant runtime access to Flyway history or future tables.
DO $$
DECLARE
    application_role text := current_setting('banking.application_role');
BEGIN
    IF application_role = current_user OR NOT EXISTS (
        SELECT 1 FROM pg_roles WHERE rolname = application_role
        AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole
        AND NOT rolreplication AND NOT rolbypassrls
    ) THEN
        RAISE EXCEPTION 'Invalid runtime database role';
    END IF;
    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA public FROM %I', application_role);
    EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM %I', application_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON public.customers, public.addresses, public.accounts, public.transactions, public.financial_idempotency TO %I', application_role);
    EXECUTE format('GRANT USAGE ON TYPE public.account_status, public.transaction_type TO %I', application_role);
END
$$;
