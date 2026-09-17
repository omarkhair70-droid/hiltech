# Master Workflow — Warehouse & Asset Custody

Status: RESEARCHING / SYSTEM MODEL v0.1

## Objective
Create a trustworthy physical/digital chain for HILTECH equipment, tools, materials, and stock.

At any moment HILTECH must be able to answer:
What exists? Where? With whom? Why? For which project? In what condition? What happened before?

---

# 1. Item Classification

Before receiving/creating inventory, classify as one of:

## Trackable Asset
Individually identified and custody-tracked.
Examples: OTDR, Fluke, fusion splicer, laptop, drill, high-value toolkit.

## Serialized Stock
Units may become project/client assets and require serial tracking.

## Consumable / Quantity Stock
Tracked primarily by quantity, unit, lot/batch where relevant.

## Service / Non-stock
Not warehouse inventory even if procured.

Classification determines movement and costing behavior.

---

# 2. Receiving

Source:
- Purchase Order
- Return from site
- Transfer from another storage location
- Company-provided existing asset initialization
- Other approved source

Capture:
- item/spec
- quantity
- serials where applicable
- condition
- supplier/source
- purchase/order link
- documents
- receiving user
- receiving location
- discrepancy

Events:
- goods.receiving_started
- goods.received
- receiving.discrepancy_created

---

# 3. Asset Registration / Tagging

For individual assets:
- generate HILTECH asset ID
- print/attach QR/barcode
- store manufacturer/model/serial
- establish baseline condition
- warranty
- calibration requirements
- maintenance rules
- value visibility by permission

Event:
- asset.registered

---

# 4. Availability & Reservation

Demand can originate from:
- Project
- Work Order
- Site
- Employee
- Maintenance/repair
- Office/facility

System evaluates:
- available quantity/assets
- existing reservations
- calibration/maintenance state
- expected return
- future project conflicts

States may include:
AVAILABLE / RESERVED / UNAVAILABLE / BLOCKED_MAINTENANCE

---

# 5. Checkout / Issue

Flow:
1. Authorized request exists.
2. Person/project/site context known.
3. Warehouse user identifies recipient.
4. Scan asset/item.
5. Verify reservation/authorization.
6. Confirm quantity and condition.
7. Record custody/movement.
8. Provide digital receipt/history.

Events:
- asset.checked_out
- stock.issued
- custody.started

---

# 6. Site / In-Use State

For trackable asset:
- current custodian
- project/site
- last confirmed location/context
- expected return
- condition

For consumable:
- issued quantity
- consumed quantity
- returnable quantity

System must distinguish custody from actual GPS certainty unless tracking hardware exists.

---

# 7. Transfer

Possible:
- person -> person
- site -> site
- warehouse -> site
- site -> warehouse
- warehouse A -> warehouse B

Transfer should require authorized acceptance where policy demands it.

Events:
- transfer.initiated
- transfer.accepted
- transfer.completed

---

# 8. Consumption

Consumable usage can come from field work.

Example:
Reserved 220m fiber
Issued 220m
Used 213m
Returned 7m

Project costing receives actual approved usage.

Events:
- stock.consumed
- stock.returned

---

# 9. Return

Process:
- identify item
- close/transfer custody
- inspect condition
- count accessories/components
- record missing/damaged state
- decide next lifecycle state

Possible result:
AVAILABLE
UNDER_INSPECTION
DAMAGED
UNDER_REPAIR
CALIBRATION_DUE
MISSING_PARTS

Events:
- asset.returned
- return.inspected

---

# 10. Damage / Loss / Incident

Damage must not simply overwrite condition.

Create Incident:
- who reported
- when
- asset
- current context
- description/evidence
- responsibility investigation if required
- repair/write-off decision

Events:
- asset.damage_reported
- asset.loss_reported
- asset.incident_opened

No automatic accusation should be made from telemetry/access/camera correlation alone.

---

# 11. Maintenance / Calibration

Track:
- maintenance schedule
- calibration expiry
- service history
- repair vendor
- cost
- certificate/document

Assets that are unsafe/unfit/expired should be excluded from reservation where policy requires.

Events:
- maintenance.due
- calibration.due
- asset.sent_for_service
- asset.service_completed

---

# 12. Stock Replenishment

Triggers:
- low stock
- project reservation shortage
- forecasted demand
- damaged/lost replacement
- manual approved request

Creates Procurement requirement rather than direct purchase.

Event:
- replenishment.requested

---

# 13. Stocktake / Cycle Count

Flow:
expected snapshot -> count -> discrepancy -> investigation -> approved adjustment.

Important:
Adjustment is a financial/operational event, not silent editing.

Events:
- stocktake.started
- stocktake.count_recorded
- stock.discrepancy_created
- stock.adjustment_requested
- stock.adjustment_approved

---

# 14. Physical Access Correlation

Future integration can record:
- warehouse door access event
- authorized identity
- timestamp
- camera event/link where permitted

These events can be correlated with inventory movement for investigation.

They do not replace checkout records.

---

# 15. Retirement / Disposal

For company assets:
request -> review -> approval -> data/security cleanup where needed -> disposal/sale/write-off -> lifecycle close.

Events:
- asset.retirement_requested
- asset.retired
- asset.disposed

---

# Invariants
- No active asset custody without a responsible identity/context.
- History is append-only/auditable.
- Quantity cannot silently become negative.
- Reserved stock cannot be double-allocated beyond permitted policy.
- Damaged/calibration-blocked assets cannot appear normally available.
- Adjustments require reason and authority.
- Asset identity survives project transfers and employee changes.

---

# Major Objects
- Inventory Item
- Asset
- Stock Item
- Location
- Warehouse
- Bin/Zone (if needed)
- Custody
- Movement
- Reservation
- Issue
- Return
- Transfer
- Consumption
- Stock Count
- Adjustment
- Maintenance Record
- Calibration Record
- Asset Incident
- Asset Tag

DISCOVERED, not final.

---

# Completion gate
Requires:
- physical warehouse walkthrough,
- sample inventory,
- actual checkout/return observation,
- current documents/Excel review,
- chosen tagging/scanning hardware strategy,
- permissions,
- state machine,
- accounting/project-cost integration,
- UI surfaces,
- build-ready modules/files,
- realistic loss/damage/offline test cases.
