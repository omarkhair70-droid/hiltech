# HILTECH Design System Architecture

Status: SYSTEM DESIGN v0.1 / TOKENS NOT VISUALLY FROZEN

## Objective
Create a reusable product language shared across Android, Windows, future iOS and client-facing surfaces without forcing identical layouts.

---

# 1. Token Layers

## Foundation Tokens
Raw scales:
- color palette,
- type sizes,
- weights,
- spacing,
- radius,
- stroke,
- elevation,
- opacity,
- motion duration/easing,
- icon sizes.

## Semantic Tokens
Meaning:
- surface.default
- surface.raised
- text.primary
- text.secondary
- border.subtle
- action.primary
- status.critical
- status.warning
- status.success
- status.info
- focus
- selected
- offline
- pendingSync

## Component Tokens
Only where component-specific override is justified.

Principle:
UI code should consume semantic tokens rather than raw hex/pixel values.

---

# 2. Theme Modes

Required:
- Light.
- Dark.
- High-contrast/accessibility strategy.

Brand accents remain HILTECH.
Do not allow arbitrary Android dynamic color to destroy operational status semantics.

Dynamic color may be evaluated for selected non-critical surfaces but is not assumed.

---

# 3. Density Modes

Potential:
- Comfortable — normal mobile/client.
- Compact — desktop operations.
- Touch-dense — tablet/warehouse, where data density and touch target both matter.

Do not expose density controls unless user value exists.
Can be surface/platform policy.

---

# 4. Component Families

## Navigation
- app shell.
- top bar.
- navigation rail/sidebar.
- bottom navigation candidate.
- breadcrumb/context path.
- command/search.

## Actions
- primary/secondary/tertiary button.
- icon action.
- split/overflow.
- destructive.
- approval action group.

## Inputs
- text.
- number/money.
- date/time.
- select/autocomplete.
- search.
- quantity.
- scan input.
- file/evidence.
- comment.

## Object
- object header.
- identity chip/tag.
- state indicator.
- owner/waiting-on.
- metadata row.
- relation/link.
- activity item.

## Lists/Data
- simple list.
- contained list.
- dense table/grid.
- filter bar.
- bulk action bar.
- pagination/virtualized list.
- inspector.

## Feedback
- inline validation.
- toast/snackbar.
- banner.
- progress.
- sync indicator.
- offline indicator.
- conflict.
- empty state.
- skeleton/loading.
- critical alert.

## Physical/Field
- scanner.
- evidence capture.
- asset passport.
- custody transfer.
- site/job card.
- test result.
- condition selector.

## Finance
- money value.
- delta.
- reconciliation state.
- approval/version marker.
- audit/source inspector.

## Visualization
- KPI.
- trend.
- distribution.
- timeline.
- topology.
- project progress.
- workload.
- status summary.

---

# 5. Interaction States

Every interactive component defines:
- default.
- hover (desktop).
- focus.
- pressed.
- selected.
- disabled.
- loading.
- error.
- read-only.
- offline unavailable where relevant.

Touch and pointer are both first-class.

---

# 6. Content States

Every object/screen design checks:
- populated.
- empty.
- loading.
- stale.
- offline cached.
- syncing.
- error.
- permission denied.
- object superseded.
- deleted/retired/closed.
- partial result.
- integration degraded.

---

# 7. Accessibility

Baseline:
- semantic labels.
- keyboard navigation.
- visible focus.
- sufficient contrast.
- screen reader order.
- reduced motion.
- scalable text.
- target sizes.
- color-independent status.
- chart alternate/table summary where needed.

---

# 8. Cross-Platform Strategy

Shared:
- semantic tokens.
- object grammar.
- icon language.
- status.
- motion meaning.
- type roles.
- core components.

Adaptive:
- nav.
- density.
- panel structure.
- pointer/touch behavior.
- keyboard shortcuts.
- desktop menus.
- mobile bottom sheets.

---

# 9. Implementation Direction

Candidate:
Compose theme/design modules.

Need:
- token source format.
- generated/shared Kotlin tokens.
- Figma/design-source alignment if Figma becomes design tool.
- screenshot/regression testing.
- light/dark/RTL preview gallery.

Final mechanism waits for client stack freeze.

## Completion gate
Requires visual token values, component specs, accessibility tests, motion tokens, RTL variants, code implementation and representative product validation.
