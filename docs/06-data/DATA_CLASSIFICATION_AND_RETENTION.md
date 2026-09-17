# HILTECH Data Classification & Retention Model

Status: DATA GOVERNANCE MODEL v0.1 / NOT LEGAL-FROZEN

## Objective
Classify every important field/object so HILTECH can make consistent decisions about:
- who can see it,
- whether it may be cached offline,
- whether it may appear in notifications/logs,
- encryption,
- exports,
- retention,
- deletion/anonymization,
- audit.

This model is architectural. Legal/accounting retention periods require Egypt/HILTECH validation before freeze.

---

# 1. Classification Levels

## PUBLIC
Safe for public website or public sharing.

Examples:
- public company profile,
- public case-study content,
- published contact information,
- public careers content.

Offline:
Yes.

Logs:
Yes if non-sensitive.

---

## INTERNAL
Routine HILTECH operational information not intended for public access.

Examples:
- ordinary project task status,
- internal team assignment,
- warehouse item availability,
- non-sensitive internal comments.

Offline:
Allowed when role/context requires.

Logs:
Identifiers may appear; payload content minimized.

---

## RESTRICTED
Business-sensitive or client-sensitive information requiring explicit context permission.

Examples:
- client project details,
- internal project cost,
- supplier pricing,
- BOQ commercial details,
- internal documents,
- client invoices,
- project risks,
- site access instructions.

Offline:
Only when needed and protected.

Notifications:
Avoid sensitive body content on lock screen.

Logs:
No raw values unless necessary and redacted.

---

## HIGHLY_RESTRICTED
Personal, payroll, bank, identity, security, credential, or similarly sensitive data.

Examples:
- salaries,
- employee bank details,
- personal IDs,
- payroll lines,
- bank account details,
- sensitive HR documents,
- camera/access logs,
- security incidents,
- authentication/session secrets.

Offline:
Minimal; only where explicitly justified and encrypted.

Notifications:
Never reveal sensitive value by default.

Logs:
Never log raw sensitive values.

Export:
Strong permission/re-auth where appropriate.

---

## SECRET / CREDENTIAL
Authentication and cryptographic secrets.

Examples:
- access tokens,
- refresh tokens,
- private keys,
- API secrets,
- bank credentials,
- service-account secrets,
- password-equivalent data.

Storage:
Secret store / platform secure storage only.

Never:
- normal DB field unless encrypted secret-store design requires,
- normal logs,
- notifications,
- analytics.

---

# 2. Action Sensitivity

Separate from data classification.

## NORMAL
Routine read/write.

## SENSITIVE
Needs explicit permission and audit.

## CRITICAL
May require:
- online authority,
- re-authentication,
- MFA,
- approval,
- reason,
- dual control.

Examples:
- payment execution,
- payroll approval,
- stock write-off,
- access credential administration,
- deleting/exporting highly restricted data.

---

# 3. Offline Storage Policy

For each field/object define:
- cacheable: YES/NO/CONDITIONAL
- encrypted-at-rest requirement
- cache TTL/freshness
- wipe trigger

Wipe triggers may include:
- logout,
- permission revoked,
- employee offboarding,
- device lost/revoked,
- org access removed.

---

# 4. Notification Policy

Notification payload categories:

## Safe Preview
Can show object title/status.

## Redacted Preview
Show generic message only.

Example:
"Payroll review requires your attention."

## No Preview
Only "Open HILTECH" style notification for very sensitive events.

Exact mapping per event later.

---

# 5. Logging Policy

Allowed:
- object ID,
- feature ID,
- error code,
- trace ID,
- timing,
- provider correlation ID.

Avoid:
- document body,
- salary amount,
- national ID,
- bank account,
- access token,
- camera image,
- message content.

Use structured redaction.

---

# 6. Export Policy

Every export defines:
- actor,
- scope,
- classification,
- reason where required,
- timestamp,
- format,
- watermark/marking if appropriate,
- audit record.

High-risk bulk export may require elevated permission/re-auth.

---

# 7. Retention Model

Retention is object-specific, not one global period.

Candidate categories:

## Operational short-lived
Examples:
- transient diagnostics,
- temporary sync artifacts,
- expired device tokens.

## Business history
Examples:
- projects,
- work,
- asset movement,
- approvals,
- procurement.

Usually long retention.

## Financial/legal
Examples:
- invoices,
- payments,
- payroll,
- contracts.

Retention determined by accounting/legal obligations.

## Security
Examples:
- access events,
- security incidents,
- camera links.

Retention determined by security/privacy policy and external system settings.

## Personal/HR
Employee records need:
- legal retention,
- minimization,
- post-employment access restrictions,
- deletion/anonymization rules where allowed.

No exact period is frozen yet.

---

# 8. Deletion / Anonymization

Do not use hard delete casually.

Possible outcomes:
- archive,
- revoke access,
- redact selected personal fields,
- anonymize,
- legal hold,
- hard delete where policy allows.

Business history may need to preserve pseudonymous actor reference even after employee departure.

---

# 9. Data Residency / Provider Review

Before production:
- identify hosting regions,
- object storage region,
- identity provider storage,
- observability storage,
- email/SMS provider handling,
- backup locations.

No provider choice may silently move HIGHLY_RESTRICTED data to unreviewed systems.

---

# 10. Object Classification Examples

| Object | Default classification |
|---|---|
| Project title/status | INTERNAL / RESTRICTED by client context |
| Project internal cost | RESTRICTED |
| Employee profile | RESTRICTED |
| Employee salary | HIGHLY_RESTRICTED |
| Payslip | HIGHLY_RESTRICTED |
| Asset serial/location | INTERNAL / RESTRICTED |
| Warehouse movement | INTERNAL |
| Supplier quote | RESTRICTED |
| Client invoice | RESTRICTED |
| Bank/payment data | HIGHLY_RESTRICTED |
| Access event | HIGHLY_RESTRICTED |
| Camera stream | HIGHLY_RESTRICTED |
| Public case study | PUBLIC |
| Access token | SECRET |

---

# 11. Freeze Gate

Before production:
- field-level classification exists for first implementation objects,
- Egypt legal/accounting/privacy review completed,
- offline encryption policy chosen,
- logging/redaction policy implemented,
- export rules tested,
- retention schedule documented,
- revocation/wipe behavior tested.
