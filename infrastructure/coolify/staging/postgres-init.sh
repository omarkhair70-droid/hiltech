#!/bin/sh
# Runs ONLY when Postgres initializes a NEW isolated staging volume.
# Never attach to a preexisting product/production database volume.
set -eu

: "${POSTGRES_USER:?}"
: "${HILTECH_APP_DB_PASSWORD:?}"
: "${HILTECH_KEYCLOAK_DB_PASSWORD:?}"
: "${HILTECH_OPENFGA_DB_PASSWORD:?}"

# psql :'var' quotes passwords as SQL literals; don't echo values or use shell SQL substitutions.
psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname=postgres \
  --set=app_password="$HILTECH_APP_DB_PASSWORD" \
  --set=kc_password="$HILTECH_KEYCLOAK_DB_PASSWORD" \
  --set=fga_password="$HILTECH_OPENFGA_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', 'hiltech_app', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'hiltech_app') \gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', 'hiltech_keycloak', :'kc_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'hiltech_keycloak') \gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', 'hiltech_openfga', :'fga_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'hiltech_openfga') \gexec

SELECT format('CREATE DATABASE %I OWNER %I', 'hiltech', 'hiltech_app')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'hiltech') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', 'hiltech_keycloak', 'hiltech_keycloak')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'hiltech_keycloak') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', 'hiltech_openfga', 'hiltech_openfga')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'hiltech_openfga') \gexec
SQL
