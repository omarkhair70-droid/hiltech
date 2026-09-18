# HILTECH Low-Fidelity Figma Prototype Status

Date: 2026-09-18
Status: ACTIVE — Figma Starter MCP call limit reached during current build session.

Figma file:
HILTECH OS — Low Fidelity Product Prototypes

File key:
`1l92hyJyOzwaudkdlUBbBt`

URL:
https://www.figma.com/design/1l92hyJyOzwaudkdlUBbBt

## Pages

### Mobile Prototypes
Page ID: `0:1`

Built:
- M01 — Mohamed Home — frame `1:3`
- M02 — Owner Approval — frame `1:5`
- M03 — Technician Today — frame `1:7`
- M04 — Warehouse Scan — frame `1:9`
- M05 — Client My HILTECH — frame `1:118`

Created, content build pending:
- M06 — Technician Job Detail — frame `2:2`
- M07 — Warehouse Checkout — frame `2:4`

### Desktop Prototypes
Page ID: `1:2`

Built:
- D01 — Ahmed Payroll — frame `1:144`
- D02 — Project Command Center — frame `1:146`

Pending creation/build when Figma MCP calls are available:
- D03 — Global Search / Command
- D04 — Shared Work Queue
- D05 — Ahmed Finance — Advances & Imprest

## Important
These are grayscale low-fidelity structural prototypes only.

The following are NOT final:
- Inter font
- colors
- visual identity
- radius
- spacing values
- iconography
- typography
- motion
- exact navigation

Inter is being used only as a temporary low-fi rendering font.

## Observations From Actual Canvas

### Mohamed Home
The screen validates:
- Needs You belongs above dashboards.
- owner mobile can remain highly compressed.
- raw Company Pulse can become vertically crowded quickly.

Design implication:
Company Pulse must prioritize a few exception-oriented values rather than becoming a grid of every KPI.

### Owner Approval
The screen validates:
- exact subject/version is understandable on mobile.
- approval flow/history can fit without making decision blind.
- bottom decision actions are viable.

Need to test:
- long evidence lists.
- larger commercial contexts.
- Arabic RTL.
- superseded/version-conflict state.

### Technician Today
The screen validates:
- Today can be extremely simple.
- READY and BLOCKED jobs can coexist in one feed.
- sync/offline status must live near the page header.
- Scan should be highly prominent without forcing every role to have a permanent Scan tab.

### Warehouse Scan
The screen strongly supports scan-first IA:
- Scan Asset dominates.
- Issue / Return / Receive / Count / Exceptions can live below.
- object identity immediately reveals action.

Need to test:
- repeated scanning.
- one-handed use.
- rapid multi-asset checkout.
- dedicated warehouse tablet.

### Client My HILTECH
The screen validates:
- client does not need internal HILTECH module language.
- Actions for You + Projects + Support is a natural client home.
- Documents/Maintenance can remain secondary.

### Ahmed Payroll Desktop
The canvas validates:
- dense table + persistent inspector is a much stronger pattern than opening each employee as a separate page.
- selected row should preserve the user's place in the payroll run.
- exception count/action belongs near run header.
- Finance sidebar can be contextual, not global ERP navigation.

### Project Command Center
The canvas validates:
- Waiting On is essential at the top.
- blockers must be real linked objects.
- three-plane desktop layout works:
  project nav / project workspace / selected inspector.
- project health needs explanation, not only a colored status.

## Next Figma Build Queue

1. M06 Technician Job Detail.
2. M07 Warehouse Checkout.
3. D03 Global Search / Command.
4. D04 Shared Work Queue.
5. Arabic RTL clone/stress test:
   - Mohamed Home
   - Technician Job
   - Ahmed Payroll desktop
   - Project Command Center
6. Conflict states:
   - stale payroll approval
   - offline work sync conflict
   - asset already checked out
7. Adaptive tablet:
   - technician list/detail
   - warehouse asset list/detail
8. Final low-fi navigation comparison.

## Gate

Low-fidelity visual prototype phase is NOT complete until:
- the pending screens above exist,
- representative Arabic RTL screens exist,
- major error/offline/conflict states are visually tested,
- mobile and desktop navigation hypotheses are compared against the resulting canvas.

The Figma MCP rate limit is an external tooling constraint only; it is not treated as a product decision.


## Pending Screen Specifications

The remaining low-fi screens are now fully specified in:

`docs/10-design/LOW_FI_PENDING_SCREEN_SPECS_2026-09-18.md`

Spec-ready:
- M06 Technician Job Detail
- M07 Warehouse Checkout
- D03 Global Search / Command
- D04 Shared Work Queue
- D05 Ahmed Finance — Advances & Imprest
- Arabic RTL stress variants
- stale/conflict/exception variants
- technician/warehouse tablet variants
- final navigation comparison gate

This removes product/interaction ambiguity while the canvas tooling is unavailable.

It does **not** replace visual validation.

## Current Figma Tooling State

Latest account/tool check on 2026-09-18:
- plan tier reported by Figma MCP: Starter
- seat reported: View
- MCP call quota: exhausted
- direct file metadata call returned the Starter MCP rate-limit error

Therefore no further Figma mutations should be attempted until the plan/seat/quota changes or resets.
