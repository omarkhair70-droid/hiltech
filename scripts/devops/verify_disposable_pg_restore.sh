#!/usr/bin/env bash
# HILTECH disposable CI-only Postgres 18.6 backup/restore contract.
# Never use this script for production or against an existing/remote database.
set -Eeuo pipefail

: "${GITHUB_ACTIONS:?This rehearsal is restricted to ephemeral GitHub Actions runners}"
: "${GITHUB_WORKSPACE:?Missing checked out CI workspace}"
[[ "${RUNNER_ENVIRONMENT:-}" == github-hosted ]] || {
  echo "BLOCKED: this rehearsal requires a disposable GitHub-hosted runner" >&2
  exit 1
}

report_dir="${GITHUB_WORKSPACE}/build/reports/devops-disposable-recovery"
mkdir -p "$report_dir"
report="$report_dir/verification.txt"
archive_dir="$report_dir/private"
mkdir -p "$archive_dir"
chmod 700 "$archive_dir"
trap 'rm -rf "$archive_dir"' EXIT
echo "DISPOSABLE_ONLY=true" > "$report"

# The only permitted target is this job's local throwaway Postgres instance.
docker run --rm --network host postgres:18.6 pg_isready -h 127.0.0.1 -p 5432 -U hiltech -d hiltech
pgtool() {
  docker run --rm --network host \
    -e PGPASSWORD=hiltech \
    -v "$archive_dir:/artifacts" \
    postgres:18.6 "$@"
}
query() {
  local database="$1" sql="$2"
  pgtool psql -X -q -A -t -v ON_ERROR_STOP=1 -h 127.0.0.1 -U hiltech -d "$database" -c "$sql" | tr -d '\r'
}

# Record disposable restored data, not company records or API credentials.
test "$(query hiltech "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='flyway_schema_history'")" = 1
query hiltech "CREATE TABLE public.hiltech_disposable_restore_probe (id integer PRIMARY KEY, proof text NOT NULL); INSERT INTO public.hiltech_disposable_restore_probe (id,proof) VALUES (1,'HILTECH_DISPOSABLE_RESTORE_MARKER');" >/dev/null
before_version="$(query hiltech "SELECT MAX(installed_rank) FROM flyway_schema_history WHERE success")"
before_proof="$(query hiltech "SELECT proof FROM hiltech_disposable_restore_probe WHERE id=1")"
[[ "$before_version" =~ ^[0-9]+$ ]] && (( before_version >= 38 )) || {
  echo "BLOCKED: migrations did not reach the expected initial HILTECH main baseline" >&2
  exit 1
}
test "$before_proof" = HILTECH_DISPOSABLE_RESTORE_MARKER

# PostgreSQL custom archive is captured within this temporary runner only.
pgtool pg_dump --format=custom --no-owner --no-acl \
  -h 127.0.0.1 -U hiltech -d hiltech -f /artifacts/hiltech-disposable.dump
pgtool pg_restore --list /artifacts/hiltech-disposable.dump >/dev/null

# Never overwrite the source database or a pre-existing restore target.
test "$(query postgres "SELECT COUNT(*) FROM pg_database WHERE datname='hiltech_disposable_restore'")" = 0 || {
  echo "BLOCKED: restore target already exists" >&2
  exit 1
}
pgtool createdb -h 127.0.0.1 -U hiltech hiltech_disposable_restore
pgtool pg_restore --exit-on-error --single-transaction --no-owner --no-acl \
  -h 127.0.0.1 -U hiltech -d hiltech_disposable_restore /artifacts/hiltech-disposable.dump

after_version="$(query hiltech_disposable_restore "SELECT MAX(installed_rank) FROM flyway_schema_history WHERE success")"
after_proof="$(query hiltech_disposable_restore "SELECT proof FROM hiltech_disposable_restore_probe WHERE id=1")"
test "$after_version" = "$before_version"
test "$after_proof" = "$before_proof"

# Deterministic full schema diff: the restored copy must retain all constraints,
# indexes, triggers and access objects present in the disposable source.
pgtool pg_dump --schema-only --no-owner --no-acl \
  -h 127.0.0.1 -U hiltech -d hiltech -f /artifacts/source-schema.sql
pgtool pg_dump --schema-only --no-owner --no-acl \
  -h 127.0.0.1 -U hiltech -d hiltech_disposable_restore -f /artifacts/restore-schema.sql
if ! pgtool diff /artifacts/source-schema.sql /artifacts/restore-schema.sql; then
  echo "BLOCKED: restored schema differs from disposable source" >&2
  exit 1
fi

{
  echo "PG_VERSION=18.6"
  echo "MIGRATION_INSTALLED_RANK=$after_version"
  echo "CUSTOM_FORMAT_ARCHIVE_VALIDATED=true"
  echo "RESTORE_INTO_SEPARATE_EMPTY_DATABASE=true"
  echo "SYNTHETIC_ROW_ROUND_TRIP=true"
  echo "RESTORED_SCHEMA_AND_CONSTRAINTS_MATCH=true"
  echo "ARCHIVE_RETAINED=false"
  echo "LIVE_DATA_ACCESSED=false"
  echo "OFFSITE_BACKUP_VERIFIED=false"
  echo "LIVE_RESTORE_DRILL_VERIFIED=false"
  echo "PRODUCTION_RECOVERY_APPROVED=false"
} >> "$report"
cat "$report"
