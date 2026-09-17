# HILTECH Delivery Dependency Graph

Status: PLANNING v0.1

## Goal
Define what must become known/proven before the next layer can freeze.

```text
Company Reality
      ↓
Human Map
      ↓
Master Workflows
      ↓
Objects / States / Events
      ↓
Permissions / Automation / Notifications
      ↓
Role Experiences
      ↓
Information Architecture
      ↓
UI/UX Reference Research
      ↓
Mobile / Desktop Surface Maps
      ↓
Offline / Sync Model
      ↓
Integration / Hardware Map
      ↓
Stack Research
      ↓
System / Client / Backend / DB Architecture
      ↓
Module Ownership / Dependencies
      ↓
Technical Spikes
      ↓
ADR Decisions
      ↓
Final Stack + Version Lock
      ↓
Design System + Motion + UI Prototypes
      ↓
Feature-to-Module Mapping
      ↓
API / Schema / File Plan
      ↓
Implementation Order
      ↓
Freeze Review
      ↓
Repository Bootstrap
      ↓
Production Code
```

## Parallelism Allowed

Some research/design can run in parallel:
- UI references and stack research.
- reality interviews and technical spikes.
- typography research and module planning.

But freeze dependencies remain:
- cannot freeze permissions without real authority validation.
- cannot freeze payroll schema without payroll reality.
- cannot freeze hardware integration without vendor inventory.
- cannot freeze final UI before representative workflows/IA.
- cannot freeze stack before critical spikes.

## Current Position

Completed as planning artifacts:
- foundation/governance.
- first-pass people maps.
- first-pass master workflows.
- first-pass object/state/event maps.
- permission/automation/notification models.
- first-pass role experiences.
- UI research passes 01-02.
- surface maps.
- offline/sync model.
- integration/hardware model.
- stack research pass 01.
- architecture model v0.1.
- module ownership/dependency model.

Next gating work:
- technical spikes.
- company reality validation.
- design-system research/prototypes.
- ADRs.
- final stack/version freeze.

Nothing above means product capabilities are COMPLETE.
