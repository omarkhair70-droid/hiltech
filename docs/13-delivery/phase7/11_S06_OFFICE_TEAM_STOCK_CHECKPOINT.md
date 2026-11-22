# Phase 7 / S06 — HILTECH Office Team Stock Purchase Need

2026-11-22. State: implementation candidate on a separate stacked branch; NOT merged, signed off, activated or released.

Purpose: a real company team can request a catalogued stocked consumable, without a fake WorkOrder or Warehouse employee assignment. This does NOT cover an external service, cash expense, asset purchase that bypasses Asset Passport, or a made-up office location.

Proven source fields: active HILTECH Organization, existing Team ID + version, current membership/relationship, current requested stock item + version/unit, actual company Warehouse receiving location + version. V0060 extends the same immutable Phase7 PurchaseRequirement discriminator; DB company-scoped FKs, team/version and delivery Warehouse insert guard, independent team source index.

Authorization: all flags default OFF. Requester needs current org membership, live team membership, and an explicitly configured ApprovalEngine requester authority key. Reviewer needs a separate current org authority key and cannot approve their own request. Procurement buyer, purchase approval/PO issuer, warehouse receiver and Finance remain separate. No guessed employees or limits.

Path: request -> submit -> independent technical ready/needs info/reject -> existing S02 buyer/RFQ/quote -> existing S03 approved PO issued strictly to selected company Warehouse -> canonical S04 Warehouse receipt + verified delivery note -> S05 3-way Finance handoff. Stock balance is visible only if current Warehouse viewing authority permits it. An existing open request for the same team/stock item is rejected under a serialized team row lock.

Windows + Android: shared team, Warehouse and stock selection; typed API, current server-side actions and errors, no manual UUID entry. Independent app flags remain OFF.

What is NOT supplied: a new fixed-asset purchase/registration journey, non-stock external services, service acceptance/milestones/2-way finance verification, company role/policy seed, real device operator acceptance, post-merge tests, supplier delivery transport proof or live Finance payment.

Acceptance tests: wrong org, revoked team member, missing authority keys, forged team/location/item, stale team/stock/destination, self-review denial, duplicate concurrent requests, direct stock receipt attempts, invoice match and rollback. Exact-head CI must pass before even engineering acceptance.
