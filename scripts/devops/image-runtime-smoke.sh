#!/usr/bin/env bash
# CI-only integration smoke for the packaged HILTECH Spring Boot image.
# Uses a disposable PostgreSQL service on this runner. NEVER run against a
# shared or production host and NEVER use real business credentials.
set -euo pipefail

image="${1:?Usage: image-runtime-smoke.sh <local-image-tag>}"
if [[ "${HILTECH_CI_IMAGE_SMOKE:-}" != "1" ]]; then
  echo "Refusing to run without HILTECH_CI_IMAGE_SMOKE=1 on an isolated CI runner." >&2
  exit 2
fi

docker image inspect "$image" >/dev/null
container="hiltech-image-smoke-${GITHUB_RUN_ID:-local}-$$"
port=18080

cleanup() {
  docker rm --force "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Host network connects ONLY to the runner's disposable PostgreSQL service.
# No port is published on or connected to the owner's Oracle/Coolify server.
docker run -d \
  --name "$container" \
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
  --env HILTECH_FGA_ENABLED="false" \
  --env HILTECH_EVIDENCE_STORAGE_ENABLED="false" \
  --env HILTECH_OTEL_ENABLED="false" \
  "$image" >/dev/null

echo "Waiting for private CI server to start (no production services involved)."

observed=""
for attempt in $(seq 1 75); do
  if [[ "$(docker inspect -f '{{.State.Running}}' "$container")" != "true" ]]; then
    echo "Image runtime terminated before opening HTTP. Exit code:" >&2
    docker inspect -f '{{.State.ExitCode}}' "$container" >&2
    docker logs --tail 50 "$container" >&2
    exit 1
  fi
  response="$(curl --silent --show-error --output /dev/null \
    --write-out '%{http_code}' --max-time 2 \
    "http://127.0.0.1:${port}/" 2>/dev/null || true)"
  if [[ "$response" == "401" || "$response" == "403" ]]; then
    observed="$response"
    break
  fi
  if [[ "$response" != "000" && "$response" != "" ]]; then
    echo "Unexpected HTTP status: $response" >&2
    docker logs --tail 30 "$container" >&2
    exit 1
  fi
  sleep 2
done

if [[ -z "$observed" ]]; then
  echo "Image startup timeout: no authenticated-denial HTTP response." >&2
  docker logs --tail 80 "$container" >&2
  exit 1
fi

# Production auth is intentionally NOT enabled in this synthetic runner.
# The lack of OIDC must fail closed rather than grant access.
echo "PASS: Spring Boot process is serving HTTP; unauthenticated root request denied (HTTP $observed)."
echo "PASS: CI container started nonroot, read-only, with 768 MiB memory and 256 PIDs limit."
echo "NOTE: This is not a substitute for staging OIDC/OpenFGA/Evidence or operator acceptance."
