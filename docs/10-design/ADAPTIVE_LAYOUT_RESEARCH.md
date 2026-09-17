# Adaptive Layout Research

Status: RESEARCHING / NOT FROZEN

## Thesis
HILTECH does not have:
- a phone layout,
- a tablet layout,
- a desktop layout
as unrelated designs.

It has object/task patterns that adapt to available space and input mode.

## Canonical layout families

### List -> Detail
Examples:
- Projects -> Project detail
- Assets -> Asset detail
- Employees -> Employee detail
- Invoices -> Invoice detail

Phone:
one pane at a time.

Large Android/tablet:
list + detail.

Desktop:
list + detail + optional inspector/activity.

### Primary -> Supporting
Examples:
- Work order + evidence
- Payroll run + exception/source inspector
- Project + activity/issues
- PO + receipt/invoice match

### Dashboard / Command
Role-aware Home.
Changes information density by width rather than merely scaling cards.

## Current Android platform direction
Material3 Adaptive supports canonical multi-pane layouts and Navigation3 integration.

HILTECH should research/use:
- list-detail
- supporting pane
- predictive back
- edge-to-edge
- RTL behavior
- window-size/posture-aware layout

## Desktop relationship
Compose desktop or other desktop implementation should preserve the same object grammar:
- list
- detail
- supporting inspector
but can add:
- keyboard
- hover
- right click/context commands
- denser tables
- wider multi-pane layouts

## RTL
Pane order must be tested in Arabic.
Do not assume a left-side list and right-side detail.

## Reference
- https://developer.android.com/develop/adaptive-apps/guides/list-detail
- https://developer.android.com/guide/navigation/navigation-3/recipes/material-listdetail
- https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive

## Freeze gate
Need representative prototypes for:
1. Projects.
2. Warehouse asset.
3. Payroll.
4. Client project.
5. Owner approval.

Across:
- phone portrait
- foldable/tablet width
- desktop
- RTL and LTR
