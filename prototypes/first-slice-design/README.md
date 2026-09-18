# HILTECH — First-Slice Interactive Design Lab

Status: ACTIVE DESIGN PROTOTYPE

This directory is intentionally **not production application code**.

Purpose:
- render HILTECH screens before KMP production implementation,
- exercise offline/conflict/permissions/RTL/adaptive behavior,
- expose bad interaction decisions while they are cheap to change,
- provide a visual/interactive reference next to the written contracts.

Current screens:
- Technician Job Detail
- Warehouse Checkout

Technician simulated states:
- READY / ONLINE
- IN PROGRESS / OFFLINE
- CONFLICT
- REWORK REQUIRED

Warehouse simulated states:
- AVAILABLE
- RESERVED SAME WORK
- CALIBRATION BLOCKED
- ALREADY CHECKED OUT
- CHECKOUT COLLISION
- SUCCESS

Current viewport proofs:
- Phone
- Tablet
- Desktop

Language:
- English
- Arabic RTL

## How to run

Open `index.html` in a browser.

No build step.
No backend.
No authentication.
No database.
No production dependencies.

## Design source of truth

The prototype visualizes contracts under:
- `docs/13-delivery/first-slice-contract-pack/`
- `docs/10-design/FIRST_SLICE_DESIGN_FREEZE_EXECUTION_PACK_2026-09-18.md`

If prototype behavior contradicts a frozen contract, fix/review the design; do not silently redefine production semantics in this lab.

## Current proof coverage

### Technician Job
- READY
- OFFLINE / IN PROGRESS
- BLOCKED
- SUBMITTED
- CONFLICT
- REWORK

### Warehouse Checkout
- AVAILABLE
- RESERVED SAME WORK
- CALIBRATION BLOCKED
- CHECKOUT COLLISION
- SUCCESS

### Configuration Center
- ACTIVE
- DRAFT
- INVALID
- ACTIVATION REVIEW
- VERSION CONFLICT
- SUPERSEDED HISTORY

### Supervisor / Engineer Review
- CLEAN SUBMISSION
- MISSING EVIDENCE
- REWORK DECISION
- STALE VERSION

### Project Command Center
- HEALTHY
- ATTENTION
- CRITICAL
- ON HOLD

### Navigation
- one-product mobile/desktop role comparison

### Cross-cutting proof
- English
- Arabic RTL
- Phone
- Tablet
- Desktop
- authoritative vs local/conflict states

Automated Playwright capture produces 37 rendered proof images in CI.
