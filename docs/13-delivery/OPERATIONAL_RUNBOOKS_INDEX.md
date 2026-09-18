# HILTECH Operational Runbooks Index

Status: REQUIRED BEFORE PRODUCTION

Runbooks must exist for at least:

## Platform
- server unhealthy.
- database unavailable.
- object storage unavailable.
- identity provider unavailable.
- authorization service unavailable.
- observability outage.

## Client / Sync
- sync backlog.
- corrupted local cache.
- stuck upload.
- device lost.
- device revoked.
- old app version.
- Windows update failure.

## Finance
- payment UNKNOWN.
- duplicate-provider callback.
- payment returned.
- payroll partial payment.
- reconciliation mismatch.

## Warehouse
- asset custody conflict.
- stock discrepancy.
- lost asset.
- scanner/QR failure.

## Integrations
- bank authentication expired.
- NVR/camera integration unavailable.
- access controller unavailable.
- push/email/SMS provider unavailable.

## Security
- suspected credential compromise.
- unauthorized access event.
- privacy/data exposure incident.

## Release
- migration failure.
- bad release rollback/forward-fix.
- feature flag emergency disable.

Each runbook must contain:
- symptoms.
- severity.
- first checks.
- safe actions.
- actions NOT to take.
- escalation owner.
- recovery verification.
- post-incident record.
