# HILTECH Hardware Strategy

Status: RESEARCHING / NOT FROZEN

## Thesis
HILTECH OS can integrate with physical infrastructure but should avoid unnecessary proprietary hardware lock-in.

Start software-first where possible.
Add hardware only when it removes meaningful operational risk/work.

---

# 1. Mobile Device

Primary field hardware:
Android phone.

Capabilities:
- camera
- QR/barcode
- NFC candidate later
- GPS where policy allows
- secure biometric/user authentication
- local storage/offline
- push
- Bluetooth candidate for tags/devices

Need reality audit:
- BYOD vs company phones
- typical Android versions
- rugged-device requirement
- camera-prohibited sites

---

# 2. Asset Labels

Phase 1 likely:
durable QR/barcode tag.

Requirements:
- stable opaque HILTECH ID
- readable after field wear
- replacement history
- anti-tamper strategy only where justified

Do not encode secrets.

---

# 3. Active Asset Tags

Future candidates:
- BLE
- UWB
- GPS depending asset class

Use only where ROI supports:
- high-value
- frequently misplaced
- mobile equipment
- large warehouse/site search cost

QR remains source identity even if telemetry exists.

---

# 4. Warehouse Access

Target:
replace single hidden physical key dependency with auditable access.

Potential:
- badge
- PIN
- biometric
- mobile credential

Exact solution depends on existing hardware/security policy.

Need:
- emergency/manual override
- event logging
- permission revocation
- power/network failure behavior

---

# 5. Cameras / NVR

Use existing/selected professional camera infrastructure.

HILTECH UI can provide authorized entry/context but should not replace NVR recording/retention responsibilities.

Need inventory:
- camera/NVR vendor
- ONVIF support
- API
- stream format
- remote access/security topology

---

# 6. Warehouse Scan Station

Potential later:
- dedicated Android/tablet
- USB/Bluetooth scanner
- label printer
- receipt/tag printer

Do not require dedicated station if phone scanning meets reality.

---

# 7. Desktop

Mohamed/Ahmed/office users:
Windows is expected current target but must validate devices/versions.

Potential requirements:
- multiple monitors
- scanner/printer
- document scan
- smart card/token from banks/government if applicable

---

# 8. Network / NOC

Future HILTECH managed-services hardware:
- SNMP/network devices
- telemetry agents
- monitoring gateways
- site probes

This is a later domain and should not contaminate first core ERP/operations architecture unless real contracts require it.

---

# 9. Hardware Identity

Every managed hardware item may have:
- hardware ID
- serial
- model/vendor
- firmware
- assigned location
- integration connection
- health
- last seen

But "HILTECH-owned asset" and "integrated infrastructure device" are different concepts and should not be conflated.

## Completion gate
Requires:
- physical warehouse/site audit
- existing CCTV/access inventory
- employee device audit
- print/tag durability tests
- security review
- procurement/maintenance cost model
- pilot hardware selection.
