#!/usr/bin/env bash
# Executed by the official entrypoint ONLY when PGDATA is empty.
set -euo pipefail
# File sources are optional only for explicit legacy test/local provisioning.
for name in DATABASE_PASSWORD FLYWAY_PASSWORD; do
  file_name="${name}_FILE"
  if [[ -v "$file_name" ]]; then
    [[ ! -v "$name" && -f "${!file_name}" && -r "${!file_name}" ]] || { echo 'Invalid secret source'; exit 1; }
    size=$(wc -c < "${!file_name}")
    [[ "$size" -gt 0 && "$size" -le 4096 ]] || { echo 'Invalid secret size'; exit 1; }
    # The operational format is a single line with no newline or BOM.
    [[ $(LC_ALL=C tr -d '\000-\037\177' < "${!file_name}" | wc -c) -eq "$size" ]] || { echo 'Invalid secret format'; exit 1; }
    value=$(cat -- "${!file_name}")
    [[ "$value" != $'\xef\xbb\xbf'* && -n "${value//[[:space:]]/}" ]] || { echo 'Invalid secret format'; exit 1; }
    printf -v "$name" '%s' "$value"
    export "$name"
    unset value
  fi
done
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
unset DATABASE_PASSWORD FLYWAY_PASSWORD
