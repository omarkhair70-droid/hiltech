# HILTECH Motion Research

Status: RESEARCHING / NOT FROZEN

## Thesis
Motion in HILTECH communicates:
- state change,
- direction,
- ownership,
- continuity,
- spatial/context transition,
- progress,
- system confidence.

Motion is not decoration added after UI design.

## Motion categories

### 1. Navigation continuity
List -> detail.
Project -> site -> asset.
Search result -> object.
Mobile pane -> detail.
Desktop inspector open/close.

### 2. State transition
PENDING -> APPROVED.
AVAILABLE -> CHECKED_OUT.
SYNCING -> SYNCED.
HEALTHY -> DEGRADED.
OPEN -> RESOLVED.

### 3. Physical movement
Warehouse -> Person -> Site.
Asset transfer.
Material issue.
Delivery received.

### 4. Network / topology
Signal/flow/impact where real topology data exists.

### 5. Feedback
Scan success.
Approval accepted.
Offline queued.
Upload progress.
Conflict needs attention.

### 6. Attention
Critical state should be visually clear without relying on infinite animation.

## Rules

1. Every motion has semantic purpose.
2. Critical information remains understandable with motion disabled/reduced.
3. Avoid ambient continuous movement in dense finance/project screens.
4. State changes need accessibility-semantic updates, not only visual alpha.
5. Prefer performant draw/state animation to expensive layout thrashing.
6. Do not animate financial values in ways that obscure the exact authoritative number.
7. Offline/sync states must never feel "done" before server authority is confirmed.

## Signature opportunities
Potential HILTECH-authored motion:
- signal path verification
- asset custody transfer line
- project milestone carry
- scan-to-asset identity reveal
- topology reroute
- evidence -> verified state

These require design prototypes before adoption.

## Native vs authored motion
### Compose-native
Default for:
- navigation
- state transitions
- list/detail
- expand/collapse
- progress
- selection
- cards/panes
- value/state feedback

### Rive or authored vector state machine
Candidate only for:
- signature interactive network/asset visualizations
- onboarding/empty states where justified
- highly reusable branded state machines

Do not use Rive as a universal UI animation layer.

## Reference
Android Compose animation guide:
https://developer.android.com/develop/ui/compose/animation/quick-guide

## Freeze gate
Need:
- motion token set
- reduced-motion behavior
- performance target
- state-transition matrix
- navigation prototypes
- scan/sync/error prototypes
- accessibility review
