#!/bin/sh
set -eu

psql \
  --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname postgres \
  --set=app_password="$HILTECH_APP_DB_PASSWORD" \
  --set=keycloak_password="$HILTECH_KEYCLOAK_DB_PASSWORD" \
  --set=openfga_password="$HILTECH_OPENFGA_DB_PASSWORD" <<'SQL'
CREATE ROLE hiltech_app LOGIN PASSWORD :'app_password';
CREATE ROLE hiltech_keycloak LOGIN PASSWORD :'keycloak_password';
CREATE ROLE hiltech_openfga LOGIN PASSWORD :'openfga_password';

CREATE DATABASE hiltech OWNER hiltech_app;
CREATE DATABASE hiltech_keycloak OWNER hiltech_keycloak;
CREATE DATABASE hiltech_openfga OWNER hiltech_openfga;
SQL
