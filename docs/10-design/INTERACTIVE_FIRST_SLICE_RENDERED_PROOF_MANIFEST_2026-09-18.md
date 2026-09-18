# HILTECH Interactive First-Slice Rendered Proof Manifest

Date: 2026-09-18  
Status: **AWAITING FINAL CI RENDER RUN**

Prototype:
`prototypes/first-slice-design/`

Branch:
`design/interactive-first-slice-20260918`

Purpose:
replace the blocked Figma-only visual gate with a rendered, stateful, browser-executed design proof.

This remains disposable design code, not production application code.

---

# Proof rule

A screenshot only counts when:

1. the HTML/JS prototype loaded in a real browser,
2. the intended screen/state was selected by the simulator,
3. the target viewport/language was applied,
4. the screenshot came from the rendered `#device` element,
5. CI produced it from the branch commit under review.

Playwright capture:
`prototypes/first-slice-design/capture.mjs`

Workflow:
`.github/workflows/design-prototype-render.yml`

Expected proof count:
**36**

---

# Technician Job

- `M06-A-tech-ready-en-phone.png`
- `M06-B-tech-offline-en-phone.png`
- `M06-C-tech-blocked-en-phone.png`
- `M06-D-tech-submitted-en-phone.png`
- `M06-E-tech-conflict-en-phone.png`
- `M06-F-tech-rework-en-phone.png`
- `R02-tech-ready-ar-phone.png`
- `R02-tech-conflict-ar-phone.png`
- `T01-tech-offline-en-tablet.png`

Review questions:
- authoritative vs local state obvious?
- offline queue visible?
- blocked is not fake-completed?
- submitted state prevents duplicate submit/start?
- conflict preserves local evidence?
- Arabic mixed codes/readability correct?
- tablet hierarchy still coherent?

---

# Warehouse Checkout

- `M07-A-warehouse-available-en-phone.png`
- `M07-B-warehouse-reserved-en-phone.png`
- `M07-C-warehouse-calibration-en-phone.png`
- `M07-E-warehouse-collision-en-phone.png`
- `M07-F-warehouse-success-en-phone.png`
- `R06-warehouse-available-ar-phone.png`
- `R06-warehouse-collision-ar-phone.png`
- `T02-warehouse-collision-en-tablet.png`

Review questions:
- Asset identity/passport visible before action?
- reservation matching same Work is understandable?
- calibration block cannot be mistaken for warning-only?
- stale cached availability collision clearly says checkout did not apply?
- new authoritative custody visible?
- next-scan flow efficient?

---

# Configuration Center

- `D06-A-config-active-en-desktop.png`
- `D06-B-config-draft-en-desktop.png`
- `D06-C-config-invalid-en-desktop.png`
- `D06-D-config-activation-en-desktop.png`
- `D06-E-config-conflict-en-desktop.png`
- `D06-F-config-history-en-desktop.png`
- `R07-config-draft-ar-desktop.png`

Review questions:
- family navigation / workspace / inspector structure clear?
- draft vs active visually distinct?
- invalid config blocks activation?
- activation review explains future-vs-historical effect?
- version conflict does not imply overwrite?
- revision history visible?
- Arabic dense admin surface remains scan-friendly?

---

# Supervisor / Engineer Review

- `D07-A-review-clean-en-desktop.png`
- `D07-B-review-missing-en-desktop.png`
- `D07-C-review-rework-en-desktop.png`
- `D07-D-review-stale-en-desktop.png`
- `R08-review-clean-ar-desktop.png`

Review questions:
- exact submitted version visible?
- EvidencePolicy requirements visible?
- missing required evidence blocks acceptance?
- rework reason is explicit/history-worthy?
- stale version forces refresh instead of decision?

---

# Project Command Center

- `D02-A-project-healthy-en-desktop.png`
- `D02-B-project-attention-en-desktop.png`
- `D02-C-project-critical-en-desktop.png`
- `D02-D-project-hold-en-desktop.png`
- `R04-project-critical-ar-desktop.png`

Review questions:
- accepted-weight progress is distinct from health?
- health exposes contributing signals?
- Waiting On is operationally useful?
- resource readiness visible?
- no manual health/progress control implied?
- Arabic dense project view works?

---

# Navigation

- `NAV-A-one-product-en-desktop.png`
- `NAV-B-one-product-ar-desktop.png`

Review questions:
- clearly one HILTECH product?
- mobile role emphasis without fragmented apps?
- desktop global/context/inspector model clear?
- Scan appears only where relevant?
- Configuration Center clearly permissioned, not a second app?
- Client surface remains restricted?

---

# CI acceptance markers

Expected log markers:

`HILTECH_DESIGN_RENDER_PASS captures=36`

`HILTECH_DESIGN_PROOF_COUNT_PASS captures=36`

Both are required before artifact review.

---

# Design Freeze decision

This manifest alone does not close Design Freeze.

After CI is green:

1. download/open the 36 rendered images,
2. review them against the questions above,
3. record any required corrections,
4. rerun until corrected proof is green,
5. update canonical design/status docs,
6. then First-Slice Freeze Review can mark the rendered-design gate PASS.

Figma may still be used later for visual-system craft, but it is not required as the proof engine if this interactive rendered review passes.
