# Warehouse — Persona & Work Map

Status: RESEARCHING / HIGH-PRIORITY DESIGN HYPOTHESIS

## Why this is a core domain
HILTECH holds high-value tools, test equipment, devices, materials, and project stock. Warehouse control is therefore not a secondary inventory screen; it is part of company risk, project execution, finance, and accountability.

## Product objective
At any moment HILTECH should be able to answer:
- What do we own?
- What quantity do we have?
- Where is it?
- Who is responsible for it right now?
- What project/site is it serving?
- What condition is it in?
- When was it last seen/used/tested/calibrated?
- What is reserved for future work?
- What is missing, damaged, low, or overdue?
- What movement created the current state?

## Two fundamentally different inventory classes
### A. Trackable Assets
Examples:
- Fluke testers
- OTDR
- fusion splicers
- power meters
- drills
- laptops
- routers/switches held as company equipment
- high-value tool kits
- vehicles or other capital equipment where applicable

Track by identity and custody.

### B. Consumables / Stock
Examples:
- fiber cable
- connectors
- patch cords
- trays
- labels
- cable ties
- installation consumables
- stock hardware sold/installed into projects

Track by quantity, lot/batch/serial when relevant, reservation, issue, consumption, return, write-off.

## Asset Passport
Every important asset should have a persistent digital identity:
- HILTECH asset ID
- QR/barcode
- category/model
- serial number
- purchase date/value where authorized
- current status
- current location
- current custodian
- condition
- warranty
- maintenance history
- calibration history/expiry where relevant
- documents/manuals
- project/site history
- movement history
- incident/damage/loss history

## Asset states
DRAFT / RECEIVED / AVAILABLE / RESERVED / CHECKED_OUT / ON_SITE / IN_TRANSFER / RETURN_PENDING / UNDER_INSPECTION / DAMAGED / UNDER_REPAIR / CALIBRATION_DUE / UNDER_CALIBRATION / MISSING / LOST / RETIRED / DISPOSED

Exact states require validation.

## Core movement rule
Never represent physical custody only by overwriting current fields. Every significant change creates an append-only movement/audit event.

Example:
Warehouse -> Mahmoud -> Project A/Site 2 -> Warehouse -> Repair -> Warehouse

Current state is derived from valid movements plus lifecycle state.

## Warehouse home
- Items due out today.
- Items due back today.
- Reservations.
- Checked-out assets.
- Overdue returns.
- Missing/damaged assets.
- Calibration/maintenance due.
- Low stock.
- Deliveries expected.
- Requests awaiting issue.
- Recent access/movement anomalies.

## Issue / Checkout flow
1. Work/project creates requirement.
2. Equipment/material request generated.
3. Stock/availability checked.
4. Asset/material reserved where applicable.
5. Person arrives for collection.
6. Scan employee or identify authenticated user.
7. Scan asset / item.
8. Confirm quantity/condition.
9. Custody/movement recorded.
10. Project/site receives resource.
11. Return/consumption closes the movement.

## Return flow
- Scan item.
- Confirm condition.
- Record returned quantity.
- Record damage/missing accessories.
- Decide AVAILABLE / INSPECTION / REPAIR / CALIBRATION.
- Close custody.
- Update project and finance implications if required.

## Materials flow
RECEIVED -> AVAILABLE -> RESERVED -> ISSUED -> CONSUMED / PARTIAL_RETURN / FULL_RETURN / SCRAPPED

Project usage should update project cost without duplicate manual data entry.

## Stocktake
Support:
- full inventory count
- cycle count
- discrepancy workflow
- expected vs actual
- reason / investigation
- approval for adjustment
- audit trail

## Restricted warehouse access
Warehouse access should eventually be controlled and auditable rather than dependent on one physical key. Technical solution is not frozen yet.

Potential integration layer:
- access-control events
- user identity
- camera/NVR events
- door state
- visitor access

Access logs and inventory movements must remain separate facts but be correlatable.

## Example anomaly
Asset movement recorded at 22:14 while no authorized warehouse access event exists -> security/warehouse anomaly, not automatic accusation.

## Desktop emphasis
- inventory tables
- reservations
- receiving
- transfers
- stocktake
- maintenance/calibration queues
- history
- purchasing context
- bulk operations
- discrepancy investigation

## Mobile emphasis
- QR/barcode scanning
- receive
- checkout
- return
- transfer
- condition photo
- stock count
- identify item
- find current custodian/location

## Offline boundary
Scanning/counting may need temporary offline support in poor connectivity. High-risk adjustments and final financial write-offs require online authority unless policy explicitly defines another model.

## Events
- asset.received
- asset.reserved
- asset.checked_out
- asset.transferred
- asset.returned
- asset.condition_changed
- asset.damage_reported
- asset.missing_reported
- asset.maintenance_due
- asset.calibration_due
- stock.received
- stock.reserved
- stock.issued
- stock.consumed
- stock.returned
- stock.adjustment_requested
- stock.adjustment_approved
- warehouse.access.event

## Connections
### Project
Requirements/reservations/usage/returns.

### Finance
Asset capitalization or expense policy, stock cost, write-offs, supplier invoice linkage, project costing.

### Procurement
Low stock / project shortage -> purchase request -> PO -> receiving.

### People
Custody and accountability tied to user/employee identity.

### Security
Access events and camera context.

## Required HILTECH reality validation
- Who currently holds warehouse key(s)?
- Who is officially responsible for stock?
- Existing paper/Excel system.
- Categories and approximate value/volume of stock.
- Serial-numbered vs quantity-based items.
- How equipment leaves/returns today.
- Whether project supervisors can collect directly.
- Lost/damaged equipment process.
- Existing cameras/access hardware.
- Existing naming/tagging conventions.
- Existing procurement/receiving documents.
- Calibration requirements for test equipment.
- Whether multiple storage locations exist.
