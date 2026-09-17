# HILTECH Accessibility Requirements

Status: SYSTEM REQUIREMENTS v0.1 / NOT IMPLEMENTATION COMPLETE

## Principle
Accessibility is a product quality requirement, not a final audit.

---

# 1. Vision / Contrast

- text contrast targets aligned with WCAG guidance.
- non-text/status controls also need adequate contrast.
- no meaning from color alone.
- light/dark modes tested.
- high-contrast strategy.

---

# 2. Touch / Pointer

- interactive hit areas large enough for field use.
- small glyph can live inside larger target.
- mouse/keyboard behavior on desktop.
- visible hover/focus/pressed.

Field UX may need larger targets than minimum accessibility guidance.

---

# 3. Keyboard

Desktop key workflows:
- navigation.
- search.
- tables.
- dialogs.
- approvals.
- forms.
- command menu.

Focus order predictable.
No keyboard trap.

---

# 4. Screen Reader / Semantics

- object status announced meaningfully.
- icon-only action has label.
- form errors associated.
- dynamic status change announced where important.
- charts have title/description/alternate data.
- hidden visual elements not left semantically active.

---

# 5. Text Scaling

Mobile supports platform text scaling without destroying critical layouts.

Dense desktop defines safe scaling/reflow behavior.

---

# 6. Motion

- respect reduced-motion setting.
- no essential information solely through motion.
- avoid sudden/jarring/continuous movement.
- state remains understandable when animations removed.

---

# 7. Cognitive Load

- strong hierarchy.
- role/context relevance.
- status indicators not everywhere.
- plain language for actions.
- confirm destructive/high-risk actions with consequence.

---

# 8. Arabic / Bidi

Accessibility includes reading order.

Test:
- screen reader Arabic.
- mixed Arabic/English.
- identifiers/numbers.
- RTL focus/navigation.
- logical ordering in tables.

---

# 9. Charts

Important visualization has:
- text context.
- accessible name/description.
- exact-data route/table.
- non-color semantics where needed.

---

# 10. Test Matrix

Representative personas:
- technician.
- finance user.
- client.
- owner.

Representative technologies:
- TalkBack Android.
- Windows screen reader/keyboard.
- large font.
- reduced motion.
- high contrast.
- RTL Arabic.

## Completion gate
Accessibility cannot be COMPLETE before real automated + manual tests on production UI.
