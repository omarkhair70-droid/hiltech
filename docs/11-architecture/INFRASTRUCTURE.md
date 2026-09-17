# HILTECH Infrastructure Architecture

Status: ARCHITECTURE MODEL v0.1 / PROVIDERS NOT FROZEN

## Objective
Define deployment responsibilities without selecting vendors prematurely.

---

# 1. Environments

Minimum:
- local development,
- CI/test,
- staging,
- production.

Optional later:
- preview/ephemeral.

Production data must not be casually copied into dev/test.

---

# 2. Initial Server Components

Expected:
- HILTECH server container/application.
- PostgreSQL.
- object storage.
- identity provider.
- authorization service if adopted.
- observability collector/backend.
- public web deployment.

Conditional:
- Redis.
- Temporal.
- search cluster.
- broker.

---

# 3. Deployment Model

Candidate:
containers for backend/infrastructure components.

Do NOT require Kubernetes at launch.

Single/managed container platform or small VM/container setup can be sufficient if:
- repeatable,
- monitored,
- backed up,
- secure,
- scalable enough.

Kubernetes only if operations/scale/team justify it.

---

# 4. Secrets

Never commit secrets.

Need:
- secret manager/environment injection.
- key rotation.
- separate environment credentials.
- least privilege.
- audit for production secret access where possible.

---

# 5. Network Security

- TLS everywhere externally.
- private database where possible.
- restricted admin endpoints.
- rate limiting/abuse protection.
- outbound integration controls.
- firewall/security groups.

---

# 6. Object Storage

Need:
- private-by-default buckets.
- signed access.
- encryption.
- version/lifecycle where needed.
- malware/file validation strategy.
- retention.
- backup/provider durability understanding.

---

# 7. CI/CD

Pipeline candidates:
- lint/static checks.
- unit tests.
- module architecture tests.
- integration tests.
- migration validation.
- security/dependency scanning.
- build server.
- build Android.
- build Windows.
- package artifacts.
- staging deploy.
- smoke tests.
- production controlled release.

No direct manual production mutation outside audited emergency path.

---

# 8. Android Distribution

Early:
internal testing / controlled distribution.

Production:
Google Play managed release.

Need:
- signing key strategy.
- Play app signing.
- staged rollout.
- crash/ANR monitoring.
- rollback/forward-fix policy.

---

# 9. Windows Distribution

Need spike:
- MSI/EXE package.
- code signing certificate.
- update channel.
- enterprise installation rights.
- auto-update or managed installer.
- rollback.
- deep link registration.

---

# 10. Availability Targets

Do not invent SLA before business validation.

Need classify:
- core API.
- auth.
- sync.
- finance.
- client portal.
- camera integration.
- notification.

External integration downtime must degrade gracefully.

---

# 11. Disaster Recovery

Need:
- DB PITR.
- object storage durability.
- identity config backup.
- authorization model backup.
- secret recovery process.
- infrastructure-as-code.
- restore runbook.
- restore drills.

---

# 12. Infrastructure as Code

Provider-independent concept:
version infrastructure definitions/config where possible.

Tool not frozen:
Terraform/OpenTofu/provider-native/etc.

---

# 13. Cost Visibility

Track:
- compute.
- DB.
- object storage.
- bandwidth.
- push/email/SMS.
- observability.
- identity/authz.
- backups.
- third-party APIs.

Cost alerts matter before scaling external clients.

## Completion gate
Requires provider decision, environment design, IaC choice, CI/CD, backup/restore test, security review, cost model and runbooks.
