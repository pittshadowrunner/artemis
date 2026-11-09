#!/usr/bin/env bash
# Runs once, on first init of the data volume. Creates the non-superuser
# application role and hands it the database. To rotate the password
# later: ALTER ROLE artemis_app PASSWORD '...' — changing the env var
# alone is not enough (lesson learned on the other stacks).
set -euo pipefail
: "${APP_DB_USER:=artemis_app}"
: "${APP_DB_PASSWORD:?APP_DB_PASSWORD is required}"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-SQL
    CREATE ROLE "${APP_DB_USER}" LOGIN PASSWORD '${APP_DB_PASSWORD}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    GRANT ALL PRIVILEGES ON DATABASE "${POSTGRES_DB}" TO "${APP_DB_USER}";
    GRANT ALL ON SCHEMA public TO "${APP_DB_USER}";
    ALTER SCHEMA public OWNER TO "${APP_DB_USER}";
    -- extensions need a superuser; create them now so Flyway (running as
    -- the app role) doesn't have to.
    CREATE EXTENSION IF NOT EXISTS pgcrypto;
    CREATE EXTENSION IF NOT EXISTS citext;
SQL
