# Transition Tables — Asset & Warehouse

Status: DOMAIN MODEL v0.1 / NOT FROZEN

---

# Trackable Asset Lifecycle

## RECEIVED -> RegisterAsset -> AVAILABLE
Preconditions:
- identity/serial captured as required
- baseline condition
- location
- tag assigned if required

Authority:
Warehouse/asset authority.

Event:
asset.registered

Offline:
Receiving draft can be local; final registration normally online.

---

## AVAILABLE -> ReserveAsset -> RESERVED
Preconditions:
- asset fit for use
- no conflicting reservation
- calibration valid if required

Authority:
Warehouse/PM request under policy.

Event:
asset.reserved

Offline:
No final reservation offline.

---

## RESERVED/AVAILABLE -> CheckoutAsset -> CHECKED_OUT
Preconditions:
- authorized recipient/context
- asset still available/reserved
- condition valid
- current authoritative version

Authority:
Warehouse.

Event:
asset.checked_out

Side effects:
- create custody movement
- update current custodian/context projection

Offline:
Can queue only under explicitly safe warehouse mode; default final authority online.

Conflict:
If already checked out -> reject with current custodian.

---

## CHECKED_OUT -> MarkOnSite -> ON_SITE
Preconditions:
- project/site context

Authority:
field/supervisor/warehouse depending process.

Event:
asset.arrived_on_site

Offline:
YES candidate.

---

## CHECKED_OUT/ON_SITE -> RequestReturn -> RETURN_PENDING
Authority:
custodian/supervisor/system reminder.

Event:
asset.return_requested

Offline:
YES.

---

## RETURN_PENDING/CHECKED_OUT/ON_SITE -> ReturnAsset -> UNDER_INSPECTION
Preconditions:
- physical item identified
- warehouse receives

Authority:
Warehouse.

Event:
asset.returned

Offline:
Scan draft possible.

---

## UNDER_INSPECTION -> AcceptReturn -> AVAILABLE
Preconditions:
- condition acceptable
- accessories complete
- calibration still valid or policy

Authority:
Warehouse.

Event:
asset.return_inspected

---

## UNDER_INSPECTION -> MarkDamaged -> DAMAGED
Preconditions:
- issue description/evidence

Authority:
Warehouse/authorized inspector.

Event:
asset.damage_reported

---

## AVAILABLE/ON_SITE/CHECKED_OUT -> ReportDamage -> DAMAGED
Preconditions:
- incident created

Authority:
custodian/supervisor/warehouse.

Event:
asset.damage_reported

Offline:
YES as incident report; server decides lifecycle transition if policy.

---

## DAMAGED -> SendForRepair -> UNDER_REPAIR
Authority:
asset/maintenance authority.

Event:
asset.sent_for_service

---

## UNDER_REPAIR -> CompleteRepair -> UNDER_INSPECTION
Preconditions:
- repair result recorded

Event:
asset.service_completed

---

## AVAILABLE -> MarkCalibrationDue -> CALIBRATION_DUE
Trigger:
date/rule/manual.

Event:
asset.calibration_due

Side effect:
block normal reservation if calibration required.

---

## CALIBRATION_DUE -> SendForCalibration -> UNDER_CALIBRATION
Event:
asset.sent_for_calibration

---

## UNDER_CALIBRATION -> CompleteCalibration -> AVAILABLE
Preconditions:
- certificate/result valid
- new expiry

Event:
asset.calibration_completed

---

## CHECKED_OUT/ON_SITE -> ReportMissing -> MISSING
Preconditions:
- incident
- last known context

Authority:
custodian/supervisor/warehouse.

Event:
asset.missing_reported

---

## MISSING -> RecoverAsset -> UNDER_INSPECTION
Event:
asset.recovered

---

## MISSING -> ConfirmLost -> LOST
Preconditions:
- investigation/approval according policy

Authority:
write-off authority.

Event:
asset.loss_confirmed

---

## AVAILABLE/DAMAGED/LOST -> RequestRetirement -> RETIREMENT_REQUESTED
Authority:
asset authority.

Event:
asset.retirement_requested

---

## RETIREMENT_REQUESTED -> RetireAsset -> RETIRED
Preconditions:
- approval
- finance/accounting effects handled

Event:
asset.retired

---

# Asset Invariants

- One active physical custody at a time.
- Checkout creates movement; never just edit current user.
- Lost/damaged history cannot be erased by later recovery/repair.
- Calibration-blocked asset cannot be offered as normal available.
- Asset tag changes do not change asset identity.
- Current location is a projection/claim with source and timestamp.

---

# Stock / Consumables

## RECEIVE
Command: ReceiveStock
Preconditions:
- receipt context
- quantity > 0
Event:
stock.received

Effect:
increase available/on-hand according receiving state.

---

## RESERVE
Command: ReserveStock
Preconditions:
- available unreserved quantity sufficient or partial policy
Event:
stock.reserved

---

## ISSUE
Command: IssueStock
Preconditions:
- recipient/project/context
- reserved/available according policy
Event:
stock.issued

Effect:
custody/project-issued quantity changes.

---

## CONSUME
Command: ConsumeStock
Preconditions:
- issued quantity
- work/project context
Event:
stock.consumed

Offline:
YES candidate from field; authoritative server validates availability/issued balance.

---

## RETURN
Command: ReturnUnusedStock
Preconditions:
- returnable unused quantity
Event:
stock.returned

---

## ADJUST
Command: RequestStockAdjustment -> ApproveStockAdjustment
Preconditions:
- count discrepancy/reason
- authority
Events:
stock.adjustment_requested
stock.adjustment_approved

Offline:
NO final adjustment.

---

# Stock Invariants

- Quantity cannot silently go negative.
- Adjustment cannot erase movement history.
- Reserved quantity cannot be double-allocated beyond explicit policy.
- Project actual consumption must trace to issue/return or authorized adjustment.
