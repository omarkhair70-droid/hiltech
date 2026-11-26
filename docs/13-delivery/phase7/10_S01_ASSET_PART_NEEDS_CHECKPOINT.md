# Phase 7 — S01 Equipment-origin spare-part and replacement-stock needs

Date: 2026-11-22
Status: **ENGINEERING CANDIDATE — NOT CLOSED OR RELEASED** (exact-head CI and human acceptance required)

## Purpose

A genuine unresolved HILTECH equipment incident or active maintenance record may
create a request for a **canonical stock item** (a spare part or recorded
replacement-stock item). The request must keep the equipment record, exact
incident/maintenance record, organization, revisions, destination Warehouse,
stock item, unit and quantity linked. It must never fabricate a WorkOrder,
an asset, an incident, an external supplier dispatch or a payment.

This is **not** purchase of external services, outsourced repairs, an office
ad-hoc request, or registration/retirement of an Asset Passport. Those are
separate business sources that cannot be represented by an invented stock item.

## Traceability and controls

- Migration **V0059** extends the same immutable Phase 7 purchase-requirement
  root with `ASSET_MAINTENANCE_OR_REPLACEMENT`, an exact company/asset FK,
  either an incident FK OR a maintenance FK, exact source and issue versions,
  and an exact canonical delivery Warehouse FK/version. A trigger rejects
  inactive, closed, cross-asset, foreign-company, off-Warehouse and stale
  source records even if a caller bypasses the API.
- Only open or under-review damage/missing/lost incidents, or in-progress
  maintenance/calibration records, are eligible. Closing or updating an issue,
  retiring or changing the asset, changing its Warehouse/delivery location,
  changing its stock version or unit invalidates current S02/S03 sourcing
  and PO authorization; it cannot silently reuse an old approval.
- Requester access is checked through the existing current
  `canOperateAssetService` relationship. An independently authorized current
  `canManageAssetService` reviewer may return, reject or technically accept
  a submitted need; the requester cannot technically approve their own need.
  Neither role automatically gets buyer, quote pricing, supplier award,
  issued PO, physical receiving or Finance authority.
- The same append-only `procurement_requirement_transition` stores
  SUBMIT, REVISE, WITHDRAW and independent technical review. Idempotent
  operation IDs and expected versions guard command retries; asset row locks
  prevent parallel duplicate active requirements for the same issue+item.
- S02 sees eligible approved technical needs through the canonical
  `ProcurementDemandSourceVerifier`. S03 performs the existing independent
  approval plus live source checks while locking the actual asset/issue,
  item and company Warehouse. An issued PO can deliver only to the source
  asset's real, still-active company Warehouse; arbitrary project
  destinations and source amendments remain unavailable.
- The KMP operator screen lets employees choose an actual Warehouse/Asset
  Passport, a real unresolved incident/maintenance, a current stock item,
  required quantity and justification. Current permissions determine which
  submit/revise/review/withdraw actions are available. Windows and Android
  use the same server rules and authenticated online-only commands.

## Independent default-OFF controls

- Server: `hiltech.procurement.requirements.enabled=false`
  and `hiltech.procurement.asset-parts.enabled=false`
- Android Gradle: `hiltech.assetPurchaseNeedsEnabled=false`
- Windows environment: `HILTECH_ASSET_PURCHASE_NEEDS_ENABLED=false`
- Existing buyer/technical reviewer, PO issuer, finance and supplier
  communication authorities stay independent and default denied.

## Release proof

The exact PR head must pass PostgreSQL V0059 migration/constraint/triggers,
S01 negative authorization and replay contracts, S02/S03 approval-source
contracts, native Windows and Android compilation, Java21 server boot and
no-conflict main compatibility. Passing them proves a build and selected
rules, not live company commissioning.

A real acceptance journey is still required: asset owner logs a real incident,
another authorized manager checks a genuine part need, an independent buyer
sources the exact stock item, authorized policy approves a PO, destination
Warehouse receives it with a verified delivery note, and Finance checks
the supplier invoice. Check wrong-company, stale incident, revoked role,
second duplicate active demand, changing asset/destination versions,
unauthorized supply and same-requester review explicitly.

No company authority bindings are invented, no real supplier messages sent,
no payments posted, and no production flags or merges are changed here.
