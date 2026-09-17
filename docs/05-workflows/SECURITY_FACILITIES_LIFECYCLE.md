# Master Workflow — Security & Facilities

Status: RESEARCHING / SYSTEM MODEL v0.1

## Objective
Model HILTECH HQ/warehouse physical security and facility context as part of HILTECH OS without pretending the OS replaces professional NVR/access-control systems.

---

# 1. Physical Areas

Examples:
- main entrance
- office
- finance room
- warehouse
- server/network room
- restricted storage
- meeting area

Objects:
- Facility
- Zone
- Door
- Camera
- Access Device

---

# 2. Identity / Access

Authorized person can have:
- badge
- PIN
- biometric
- mobile credential
depending on actual hardware.

HILTECH business rule determines entitlement.
Access controller enforces physical action.

---

# 3. Access Event

Record/context:
- door/zone
- identity/credential if provided
- granted/denied
- timestamp
- source controller
- external event ID

Access event is not the same as attendance unless HR policy explicitly maps it.

---

# 4. Camera Context

HILTECH may expose:
- camera list
- live view
- event deep link
- snapshot/context
depending on VMS/NVR capability.

NVR/VMS owns recording/retention.

---

# 5. Warehouse Correlation

Potential:
asset movement at 22:14
+
warehouse door event at 22:12
+
camera event link

This creates investigation context.

It does NOT automatically assign blame.

---

# 6. Visitor

Potential flow:
visitor registered -> host notified -> temporary access/escort -> arrival/departure recorded.

Only implement if reality justifies it.

---

# 7. Security Incident

Sources:
- unauthorized access
- door forced/open too long
- warehouse discrepancy
- camera event
- manual report

Flow:
incident -> triage -> evidence -> investigation -> resolution -> audit.

---

# 8. Device/Integration Health

Monitor:
- controller offline
- NVR unavailable
- camera unavailable
- credential sync failure

User should know whether issue is HILTECH or external security system.

---

# 9. Permissions

Highly restricted:
- live cameras
- access events
- security incidents
- credential management

Viewing sensitive security data may itself be audited.

---

# 10. Remote Actions

Potential future:
- activate/deactivate credential
- temporary access
- door action

Critical:
actual hardware/security review required.
No remote physical control until vendor/security/failure behavior is validated.

---

# Major Objects

- Facility
- Security Zone
- Door
- Camera
- Access Credential
- Access Entitlement
- Access Event
- Visitor
- Security Incident
- Security Integration Health

---

# Completion Gate

Requires:
- physical HQ/warehouse audit,
- vendor/model inventory,
- NVR/access-control APIs/protocols,
- legal/privacy/retention review,
- exact authority,
- failure/offline behavior,
- integration spike,
- incident/credential tests.
