# UX-128 Phase 7 unified native bridge — starting checkpoint

Base: `integration/phase7-premerge-candidate-20261122` PR #163, exact commit `dbc612f001696ebe85e5e9b0c58883d2af51f4a5`. This is the current assembled Engineering P7 S01-S06 source over main, NOT the older UX S04 base.

Purpose: carry the operational semantic Design System (real Compose source, not PNG) forward on the actual current Engineering tree, then apply it progressively to the already-functional authorized sections without replacing canonical domain, approval, PO, receiving or invoice logic. Preserve supplier communication, receiving evidence/rejection, supplier invoice matching, warehouse replenishment, asset parts and office team stock modules in Engineering.

First native usage: `SupplierPoCommunicationSection.kt` now consumes shared semantic `HiltechPanel` and `HiltechSectionHeading` in the genuine previously-existing KMP Compose operator surface. Existing typed APIs, authorization, audit, exact external attestation conditions, idempotency and server evidence models remain identical. No Main.kt, database migration or backend changes.

Source from UX #156: `HiltechOperationalDesign.kt`. UX #134/#142/#144/#152/#154 stay exploratory references; #156 stays an older-base Draft reference. Subsequent scoped patches should port existing source-backed buyer/issuer view improvements into this Engineering-combined branch, preserving later S03-S06 functionality.

Status: bridge is a review/QA target, NOT merged, NOT staging or production. An exact-head shared/desktop build is required before the branch may be treated as verified. No company permissions/flags or real payments/supplier dispatch have been changed. Phase7 remains an Engineering closure lane, independent from the Design System track.