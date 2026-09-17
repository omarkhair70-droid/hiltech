# HILTECH Integration Map

Status: RESEARCHING / SYSTEM MODEL v0.1

## Principle
HILTECH owns product experience and business truth.
External systems remain authoritative for the capabilities they physically/regulatorily control.

Use an integration boundary/adapters rather than spreading vendor-specific logic across product modules.

---

# 1. Banking / Payments

Possible capabilities:
- payment/batch handoff
- result/status
- transaction/reconciliation input
- statement/import

Authority:
bank remains authoritative for money movement.

Unknown:
actual HILTECH bank(s), API/host-to-host/file capabilities and signing authority.

Adapter concept:
BankingGateway.

---

# 2. CCTV / NVR / VMS

Possible:
- camera registry
- live stream
- recorded event/deep link
- health
- snapshots/events where legally/policy permitted

Potential standards/vendor paths:
- ONVIF where supported
- vendor SDK/API
- NVR/VMS API

HILTECH should not build a video-management engine if existing VMS/NVR already provides it.

Adapter:
VideoSecurityGateway.

---

# 3. Access Control

Possible:
- door/zone registry
- access event
- credential activation/deactivation
- temporary visitor credential
- warehouse event correlation

Authority:
physical access controller remains authoritative.

Adapter:
AccessControlGateway.

---

# 4. QR / Barcode

Core local integration:
- camera scanning
- printed asset tags
- stock labels
- project/site identifiers

HILTECH owns ID semantics.

Do not make QR contain sensitive mutable data.
Prefer opaque/stable identifier resolved by authorized app.

---

# 5. Asset Location / BLE / GPS / IoT

Future:
- BLE tag
- gateway observation
- GPS vehicle/asset
- sensor readings

Important:
represent observation uncertainty honestly:
last detected / observed at time
not false exact location.

Adapter:
TelemetryGateway.

---

# 6. Test Equipment

Potential:
- OTDR file/result import
- Fluke report/result import
- calibration certificate
- serial association

Prefer structured import/API where vendor allows.
Fallback: attach signed/exported report linked to test object.

Adapter:
TestEquipmentGateway.

---

# 7. Push Notifications

Provider handles delivery transport.
HILTECH owns:
- notification policy
- user/channel preference
- sensitive-content policy
- deep link
- durable Inbox state

Adapter:
PushGateway.

---

# 8. Email

Use for:
- external invites
- formal notices
- document links
- supplier/client workflows
- fallback

HILTECH Inbox remains system-of-record for internal actionable work where applicable.

Adapter:
EmailGateway.

---

# 9. SMS

Use only where justified:
- external/field operational fallback
- OTP if chosen provider/auth path requires
- critical notice by policy

Not default replacement for app push.

Adapter:
SmsGateway.

---

# 10. Public Website

Public hiltech-eg.com:
- RFQ
- contact
- careers/applications
- case studies

Qualified submissions can create HILTECH objects instead of becoming disconnected email.

Adapter/API:
PublicWebGateway or normal authenticated backend endpoint with abuse controls.

---

# 11. Identity / Authentication

Potential external identity provider architecture to be researched/frozen later.

Must support:
- employees
- clients
- suppliers
- subcontractors
- sessions/devices
- MFA/passkeys path
- revocation

External identity component does not own business role/organization truth.

---

# 12. Accounting / Tax / Government

Unknown until company reality audit.

Do not invent integration before knowing:
- current accounting software
- e-invoice/e-tax processes
- legal requirements
- accountant workflow

Reserve adapter boundary.

---

# 13. Maps / Geolocation

Potential:
- site location
- technician navigation
- project map
- fleet/asset view

Provider not frozen.
Privacy/location collection policy required before implementation.

---

# 14. Integration Health

Every external connection should expose:
- connected/disconnected
- last successful call/event
- auth expiry
- latency/error
- degraded state
- retry/backlog

Users must be able to distinguish:
"HILTECH failed"
from
"Bank/NVR/provider unavailable".

---

# 15. Integration Event Audit

Critical external actions store:
- request identity
- actor
- external correlation ID
- provider response category
- retries
- final state

Avoid storing secrets/raw sensitive payload unnecessarily.

## Completion gate
Each integration requires:
- real provider/vendor identification,
- official API/protocol research,
- sandbox/test path,
- auth/security model,
- failure/retry semantics,
- adapter contract,
- observability,
- runbook.
