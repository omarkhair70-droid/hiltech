# HILTECH Status Semantics

Status: DESIGN MODEL v0.1 / COLORS NOT FROZEN

## Principle
Status is business meaning first, color second.

Never rely on color alone.

Use combinations of:
- label,
- icon/symbol,
- shape,
- tone,
- position,
- sometimes motion for transition.

---

# 1. Attention vs Lifecycle

Do not confuse:

## Lifecycle State
Example:
DRAFT / ACTIVE / CLOSED.

## Health
HEALTHY / AT_RISK / CRITICAL / UNKNOWN.

## Action State
WAITING_ON_YOU / WAITING_CLIENT / WAITING_SUPPLIER.

## Sync State
LOCAL / QUEUED / SYNCING / SYNCED / CONFLICT.

## Result
SUCCESS / FAILED / PARTIAL.

These need different visual semantics rather than one universal red/yellow/green field.

---

# 2. Attention Levels

## Critical
Immediate action / serious failure.

## Warning
Risk or intervention likely.

## Attention
Action needed but not critical.

## Informational
Meaningful context.

## Normal
Stable/expected.

## Unknown
No reliable current state.

## Inactive / Draft
Not active yet.

Exact names can vary by domain.

---

# 3. Color Rule

Semantic status colors can support recognition but must pair with text/symbol.

Avoid:
- green meaning both "paid" and "online" and "approved" with no label.
- red for every negative concept.
- colorized every row.

High density surfaces should use status sparingly.

---

# 4. Consolidated Status

If summary represents many children:
surface the highest meaningful risk, but preserve explanation.

Example:
Project health Critical because Site 3 is blocked.

Do not reduce complex portfolio to unexplained red dot.

---

# 5. Financial Deltas

Positive/negative is contextual.

Example:
Revenue + can be positive.
Cost + may be unfavorable.

Do not assume green = plus and red = minus without semantic interpretation.

---

# 6. Unknown Is First-Class

For external integrations:
UNKNOWN / STALE / PROVIDER_UNAVAILABLE are not the same as FAILED.

Never turn uncertainty into success/failure.

---

# 7. Accessibility

Reference principles from Carbon:
- status indicators benefit from color + symbol/shape + descriptive label.
- overusing status indicators creates cognitive load.

HILTECH should reserve strong indicators for information worth scanning.

## Freeze gate
Need actual semantic palette, icons/shapes, light/dark/high-contrast variants, finance/domain review and accessibility testing.
