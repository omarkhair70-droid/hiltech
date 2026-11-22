#!/usr/bin/env bash
# HILTECH — read-only Docker/Coolify host capacity and health snapshot.
# Run on an authorized Linux host. Does NOT modify Docker, host settings, secrets or deployments.
# Detailed mode prints container names/status; do not paste this output into public issues.
set -euo pipefail

usage() {
  echo "Usage: bash scripts/devops/coolify-readonly-audit.sh [--details]"
  echo "Read-only Docker, CPU, RAM, swap, disk and container-health summary."
}

details=false
case "${1:-}" in
  "") ;;
  --details) details=true ;;
  --help|-h) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac
if [[ $# -gt 1 ]]; then usage >&2; exit 2; fi

printf 'HILTECH_READONLY_HOST_AUDIT\n'
printf 'UTC=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'CPU_COUNT=%s\n' "$(nproc)"
printf '\nMEMORY_SWAP\n'
free -h
printf '\nROOT_DISK\n'
df -h /
printf '\nPROCESS_COUNT=%s\n' "$(ps -e --no-headers | wc -l | tr -d ' ')"
printf '\nDOCKER_ACCESS\n'

docker_cmd=(docker)
if docker ps -q >/dev/null 2>&1; then
  echo 'MODE=non-privileged Docker API access'
elif command -v sudo >/dev/null 2>&1 && sudo -n docker ps -q >/dev/null 2>&1; then
  docker_cmd=(sudo -n docker)
  echo 'MODE=existing passwordless sudo for read-only Docker commands'
else
  echo 'DOCKER_API_UNAVAILABLE=YES; no privilege or password prompt attempted'
  echo 'HILTECH_READONLY_HOST_AUDIT_END'
  exit 3
fi

"${docker_cmd[@]}" --version
printf 'RUNNING_CONTAINERS=%s\n' "$("${docker_cmd[@]}" ps -q | wc -l | tr -d ' ')"
printf 'TOTAL_CONTAINERS=%s\n' "$("${docker_cmd[@]}" ps -aq | wc -l | tr -d ' ')"
printf 'UNHEALTHY_CONTAINERS=%s\n' "$("${docker_cmd[@]}" ps -q --filter health=unhealthy | wc -l | tr -d ' ')"
printf '\nDOCKER_DISK_SUMMARY\n'
"${docker_cmd[@]}" system df

if $details; then
  printf '\nCONTAINER_NAMES_AND_HEALTH__PRIVATE_OUTPUT\n'
  "${docker_cmd[@]}" ps -a
  printf '\nLIVE_RESOURCE_USAGE__PRIVATE_OUTPUT\n'
  "${docker_cmd[@]}" stats --no-stream
fi

printf '\nHILTECH_READONLY_HOST_AUDIT_END\n'
