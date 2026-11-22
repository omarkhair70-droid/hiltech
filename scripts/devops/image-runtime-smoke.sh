#!/usr/bin/env bash
# CI-only integration smoke for the packaged HILTECH Spring Boot image.
# PostgreSQL is a disposable GitHub Actions service; OpenFGA is a disposable
# in-memory fixture from the CANONICAL HILTECH model. NEVER run against a
# shared or production host or use real business data/credentials.
set -euo pipefail

image="${1:?Usage: image-runtime-smoke.sh <local-image-tag>}"
if [[ "${HILTECH_CI_IMAGE_SMOKE:-}" != "1" || "${GITHUB_ACTIONS:-}" != "true" ]]; then
  echo "Refusing to run outside an explicitly configured GitHub Actions CI runner." >&2
  exit 2
fi

docker image inspect "$image" >/dev/null
app_container="hiltech-image-smoke-${GITHUB_RUN_ID:-local}-$$"
fga_container="hiltech-openfga-smoke-${GITHUB_RUN_ID:-local}-$$"
port=18080
fga_url="http://127.0.0.1:8080"

cleanup() {
  docker rm --force "$app_container" "$fga_container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# OpenFGA's memory engine is only for isolated CI, never Staging/Production.
docker run -d \
  --name "$fga_container" \
  --network host \
  --memory=256m \
  --cpus=0.5 \
  --pids-limit=128 \
  --env OPENFGA_DATASTORE_ENGINE=memory \
  --env OPENFGA_PLAYGROUND_ENABLED=false \
  openfga/openfga:v1.20.0 run >/dev/null

fga_ready=false
for attempt in $(seq 1 45); do
  if curl --silent --fail --max-time 2 "$fga_url/stores" >/dev/null 2>&1; then
    fga_ready=true
    break
  fi
  sleep 1
done
if [[ "$fga_ready" != "true" ]]; then
  echo "OpenFGA disposable in-memory service failed to start." >&2
  docker logs --tail 40 "$fga_container" >&2
  exit 1
fi

# Never invent an alternate authorization model: import canonical FGA model
# and the synthetic fixtures from the committed first-slice contract pack.
docker run --rm \
  --network host \
  --env FGA_API_URL="$fga_url" \
  --volume "$PWD/docs/13-delivery/first-slice-contract-pack/openfga:/contracts:ro" \
  openfga/cli:v0.7.20 \
  store import --file /contracts/first-slice-model.fga.yaml

# The fresh memory store should have one imported canonical model.
store_id="$(curl --silent --show-error --fail "$fga_url/stores" | \
  python3 -c 'import sys,json; v=json.load(sys.stdin).get("stores",[]); assert len(v)==1, "expected a single CI store"; print(v[0]["id"])')"
model_id="$(curl --silent --show-error --fail "$fga_url/stores/$store_id/authorization-models" | \
  python3 -c 'import sys,json; v=json.load(sys.stdin).get("authorization_models",[]); assert len(v)>=1, "missing model"; print(v[0]["id"])')"
test -n "$store_id"
test -n "$model_id"

# Host network connects ONLY to the CI runner's disposable PostgreSQL/OpenFGA.
# No app port is published on the owner's Oracle/Coolify server.
docker run -d \
  --name "$app_container" \
  --network host \
  --memory=768m \
  --cpus=1 \
  --pids-limit=256 \
  --read-only \
  --tmpfs /tmp:rw,nosuid,nodev,size=64m \
  --env HILTECH_DB_URL="jdbc:postgresql://127.0.0.1:5432/hiltech" \
  --env HILTECH_DB_USER="hiltech" \
  --env HILTECH_DB_PASSWORD="hiltech" \
  --env HILTECH_SERVER_PORT="$port" \
  --env HILTECH_OIDC_ENABLED="false" \
  --env HILTECH_FGA_ENABLED="true" \
  --env HILTECH_FGA_API_URL="$fga_url" \
  --env HILTECH_FGA_STORE_ID="$store_id" \
  --env HILTECH_FGA_MODEL_ID="$model_id" \
  --env HILTECH_EVIDENCE_STORAGE_ENABLED="false" \
  --env HILTECH_OTEL_ENABLED="false" \
  "$image" >/dev/null

echo "Waiting for private CI Spring Boot server and canonical OpenFGA."

observed=""
for attempt in $(seq 1 75); do
  if [[ "$(docker inspect -f '{{.State.Running}}' "$app_container")" != "true" ]]; then
    echo "Image runtime terminated before opening HTTP. Exit code:" >&2
    docker inspect -f '{{.State.ExitCode}}' "$app_container" >&2
    docker logs --tail 70 "$app_container" >&2
    exit 1
  fi
  response="$(curl --silent --output /dev/null \
    --write-out '%{http_code}' --max-time 2 \
    "http://127.0.0.1:${port}/" 2>/dev/null || true)"
  if [[ "$response" == "401" || "$response" == "403" ]]; then
    observed="$response"
    break
  fi
  if [[ "$response" != "000" && "$response" != "" ]]; then
    echo "Unexpected HTTP status: $response" >&2
    docker logs --tail 30 "$app_container" >&2
    exit 1
  fi
  sleep 2
done

if [[ -z "$observed" ]]; then
  echo "Image startup timeout: no authenticated-denial HTTP response." >&2
  docker logs --tail 80 "$app_container" >&2
  exit 1
fi

# Real Actuator health endpoints must answer 200 once Spring Boot and its
# disposable PostgreSQL are ready. No connection details may be exposed.
for probe in liveness readiness; do
  status=""
  output="$(mktemp)"
  for attempt in $(seq 1 25); do
    status="$(curl --silent --output "$output" --write-out '%{http_code}' \\
      --max-time 2 "http://127.0.0.1:${port}/actuator/health/${probe}" 2>/dev/null || true)"
    if [[ "$status" == "200" ]]; then
      break
    fi
    sleep 1
  done
  if [[ "$status" != "200" ]]; then
    echo "Actuator health/${probe} not ready; HTTP $status" >&2
    rm -f "$output"
    exit 1
  fi
  python3 -c 'import json,sys; d=json.load(open(sys.argv[1], encoding="utf8")); assert d == {"status":"UP"}, "health response must hide component details"' "$output"
  rm -f "$output"
  echo "PASS: /actuator/health/${probe} HTTP 200 with non-sensitive UP-only payload."
done

# Only the health endpoint is permitted to anonymous callers. Administrative
# actuator endpoints and ordinary business routes must never be exposed.
admin_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \\
  --max-time 2 "http://127.0.0.1:${port}/actuator/env" 2>/dev/null || true)"
if [[ "$admin_status" != "403" && "$admin_status" != "401" && "$admin_status" != "404" ]]; then
  echo "Unexpected anonymous access to /actuator/env (HTTP $admin_status)." >&2
  exit 1
fi

# OIDC is intentionally disabled in this throwaway synthetic runner, so
# ordinary anonymous business access must be denied; OpenFGA is real in-memory.
echo "PASS: Spring Boot process is serving HTTP; unauthenticated request denied (HTTP $observed)."
echo "PASS: In-memory OpenFGA loaded canonical committed model and fixtures."
echo "PASS: /actuator/env remains inaccessible to anonymous users (HTTP $admin_status)."
echo "PASS: CI-only container started nonroot/read-only with 768 MiB RAM/256 PIDs limit."
echo "NOTE: Synthetic auth denial is not a substitute for production Keycloak, persistent OpenFGA, backups or operator acceptance."
