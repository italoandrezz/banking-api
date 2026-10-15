#!/usr/bin/env bash
# Executed by the official entrypoint ONLY when PGDATA is empty.
set -euo pipefail
: "${DATABASE_USERNAME:?}" "${DATABASE_PASSWORD:?}" "${FLYWAY_USERNAME:?}" "${FLYWAY_PASSWORD:?}"
for role in "$POSTGRES_USER" "$DATABASE_USERNAME" "$FLYWAY_USERNAME"; do
  [[ "$role" =~ ^[a-z][a-z0-9_]{0,62}$ ]] || { echo 'Invalid database role name'; exit 1; }
done
[[ "$POSTGRES_USER" != "$DATABASE_USERNAME" && "$POSTGRES_USER" != "$FLYWAY_USERNAME" && "$DATABASE_USERNAME" != "$FLYWAY_USERNAME" ]] || { echo 'Three distinct database roles are required'; exit 1; }
[[ "$POSTGRES_PASSWORD" != "$DATABASE_PASSWORD" && "$POSTGRES_PASSWORD" != "$FLYWAY_PASSWORD" && "$DATABASE_PASSWORD" != "$FLYWAY_PASSWORD" ]] || { echo 'Distinct database passwords are required'; exit 1; }
psql -X --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv app_user DATABASE_USERNAME
\getenv app_password DATABASE_PASSWORD
\getenv migration_user FLYWAY_USERNAME
\getenv migration_password FLYWAY_PASSWORD
BEGIN;
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'app_user', :'app_password') \gexec
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'migration_user', :'migration_password') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I, %I', current_database(), :'app_user', :'migration_user') \gexec
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SELECT format('ALTER SCHEMA public OWNER TO %I', :'migration_user') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'app_user') \gexec
SELECT format('ALTER ROLE %I IN DATABASE %I SET search_path TO public', :'app_user', current_database()) \gexec
SELECT format('ALTER ROLE %I IN DATABASE %I SET search_path TO public', :'migration_user', current_database()) \gexec
SELECT format('ALTER ROLE %I IN DATABASE %I SET banking.application_role TO %L', :'migration_user', current_database(), :'app_user') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC', :'migration_user') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE USAGE ON TYPES FROM PUBLIC', :'migration_user') \gexec
COMMIT;
SQL
