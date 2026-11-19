# Phase 5 / Slice 08 — Inventory Adjustment Approval Decision

Date: 2026-11-19
Status: OWNER CLARIFIED / IMPLEMENTATION AUTHORIZED

Inventory Adjustment approval is flexible. Product authority must not be tied to Mohamed, Ahmed, or another current person's name.

The executable authority is INVENTORY_ADJUSTMENT_APPROVER. A current principal may be one USER or one TEAM. When multiple people may approve, the canonical configuration is a TEAM where any current member may decide. Personnel changes update authority configuration, not product code.

Controlled contract: policy INVENTORY_ADJUSTMENT_STANDARD; subject INVENTORY_ADJUSTMENT_REQUEST; material subject version inventory_adjustment_request.version; explicit SINGLE decision; reason code INVENTORY_ADJUSTMENT; adjustment reason is the safe Approval summary.

Requester authority never implies final approval authority. Even when requester and approver are the same identity, the correction retains a canonical ApprovalRequest and explicit decision rather than silently bypassing audit.

Authority is resolved from the current Warehouse USER/TEAM binding. Zero or ambiguous current authority fails closed. Multiple humans use one TEAM for the current SINGLE engine contract. Decision time revalidates current authority. Final Stock adjustment is a separate command after canonical APPROVED outcome.

The privileged adjustment authority remains outside the ordinary delegated Warehouse grant API. Audited trusted provisioning sets or replaces it and ensures the Approval-owned policy exists.

This resolves the owner-authority and Approval subject/policy gaps from the prior Final Gap Review. ADJUSTMENT application and terminal reconciliation remain implementation work until exact-head verified.