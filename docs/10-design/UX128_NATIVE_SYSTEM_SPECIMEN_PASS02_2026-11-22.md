# UX-128 — Native Compose System Specimen / Pass 02

Status: **EXPERIMENT / NOT PRODUCTION / NOT A DESIGN-SYSTEM FREEZE**
Start base: branch `ux/128-foundations-reference-audit-20261122`, old tested head `fd2c0c58b6b22f77cba5139148930555bb008841`; no rebasing or touch to concurrent Engineering/DevOps code.
Date: 2026-11-22
Coordination: Issue #128; companion Draft PR #152.

## What changed and why

Following Pass 01's source and design research, this pass provides a reproducible **native Kotlin/Compose specimen renderer**, instead of sketches or a text-only design-system document. It imports the actual shared KMP contracts `HuxStatus`, `HuxIconIntent`, `HuxDensityCandidate`, `HuxLocale`, `HuxThemeCandidate` from the PR. These are candidate presentation contracts, not a business-state mapper, selected icon glyphs, approved typography, or production skin.

- `apps/desktopApp/src/test/kotlin/com/hiltech/desktop/evidence/UX128SystemSpecimenEvidence.kt`: actual Compose Foundation layout / BasicText components with an isolated candidate palette, rendered headlessly using Compose `ImageComposeScene`; no AWT Robot foreground capture.
- `docs/10-design/ux128-system-specimens.init.gradle`: QA-only JavaExec task. No production `build.gradle.kts`, app entrypoint or shared API changed.
- No source data, auth policy, server response, stock mutation, payment, WorkOrder action, flag or deployment is touched.

The screenshots use synthetic, explicitly labeled examples. They do not prove a user can do the corresponding task; acceptance must happen in a native app session and source-authorized workflow.

## Gallery matrix

| Output PNG | Viewport | Language / direction | Candidate theme | Study |
|---|---:|---|---|---|
| 01-phone-ar-light-status | 430 × 960 | Arabic RTL | Light | Independent status axes + semantic labels |
| 02-phone-ar-dark-status | 430 × 960 | Arabic RTL | Dark | Legibility and status distinction |
| 03-phone-ar-light-type | 430 × 960 | Arabic RTL with Latin codes | Light | Mixed-script and numeric specimen |
| 04-phone-ar-light-icons | 430 × 960 | Arabic RTL | Light | Domain icon meanings, labels and touch density |
| 05-windows-en-light-status | 1180 × 880 | English LTR | Light | Compact two-column state language |
| 06-windows-en-dark-status | 1180 × 880 | English LTR | Dark | State distinction |
| 07-windows-ar-high-contrast | 1180 × 880 | Arabic RTL | High Contrast | Candidate contrast; **not** WCAG-verified |
| 08-windows-ar-light-type | 1180 × 880 | Arabic RTL / Latin codes | Light | Desktop mixed-script dense type |
| 09-windows-en-light-icons | 1180 × 880 | English LTR | Light | Icons, labels, compact target |

**Caveat:** Rendering a desktop Compose scene at a phone-like *pixel width* is **not an Android app run**, even if it resembles an Android size. Windows screenshots are from Compose's software UI rendering, not the signed-in production app. Native font fallback is not a font comparison; licensing and actual candidate fonts remain open.

## Exact evidence and QA gates

Run from an isolated authorized Windows QA clone:
```powershell
.\gradlew.bat -I docs/10-design/ux128-system-specimens.init.gradle :apps:desktopApp:renderUx128SystemSpecimens --max-workers=2 --no-daemon --console=plain
```
The JavaExec program validates each image's pixel dimensions and non-trivial byte count, emits `UX128_SYSTEM_RENDER_PASS` for each, then requires nine PNGs and prints `UX128_SYSTEM_GALLERY_PASS`. Evidence is stored **locally** in `apps/desktopApp/build/reports/ux128-system-specimens`; images are not claimed as uploaded to GitHub. Build status / exact tested SHA must be recorded only after process exit. The gallery requires a visual human audit: text clipping, glyph fallback, RTL, contrast and optical balance cannot be proven merely from byte counts.

## Preliminary questions / must-check findings

1. **Status:** Does UI distinguish `ACTION_WAITING_ON_YOU`, `SYNC_QUEUED`, `SYNC_CONFIRMED` and `RESULT_SUCCESS` without falsely implying authoritativeness?
2. **Arabic and identifiers:** Can people read `WO-QA-05`, `ODF-07`, serial numbers, dates and currency inside Arabic text without reversed digits or truncation? Test locale, bidi and 200% font scales.
3. **Icon meaning:** Current letter-marker boxes are deliberately **not drawn icons**. Evaluate existing shared icon family and custom glyph candidates (Rack/ODF/OTDR/Fluke/Asset Passport) with real operators before choosing shapes.
4. **Density:** Compare 40/48/56dp proposed hit targets on a *physical* field device and actual desktop keyboard/hover use; on-screen rendering alone does not establish usability.
5. **High contrast:** White-on-black helps provide a test baseline, but a normative contrast check must measure actual foreground/background pairs, non-text edges and semantic focus states before acceptance.
6. **Token freeze:** The temporary palette is a **specimen-specific testing skin**; no global design-system HEX palette, custom font, motion values or icon asset has been approved.

## Next controlled research / implementation gates

- **Pass 03: visual QA:** inspect all nine PNGs directly and fix actual artifact clipping and Arabic baseline issues. Provide image evidence plus checkpoint manifest (file hashes, exact code SHA, dimensions).
- **Pass 04: native cross-role specimens:** use the *current* Engineering source contracts, not older PR UI code, to render the same Warehouse, Field and PM object states as composition examples. Validate source authority and stale/queued behavior.
- **Pass 05: type/icon comparative tests:** side-by-side open-license Arabic fonts and glyph families, real 200% scaling, 3 themes, RTL, keyboard focus and accessible role labels. Custom icons require operator comprehension, not stylistic approval.
- **Integration:** after owner review, a narrow status/toast component with domain adapters may be extracted and re-applied to the latest Engineering `main` (not merged wholesale from this stale research branch).
- **Finish:** final design-system values and motion/haptic language are Phase13-wide decisions, independent of transient reference artifacts.

**No merge, deploy or production change without explicitly coordinated Engineering review and exact-head CI.**

## 6. Actual screenshot review / Pass 02 outcome

The initial renderer used Material3 classes not present on the Desktop QA test classpath and failed compilation. **It was repaired in the isolated renderer** to use Compose Foundation `BasicText`, layout, background and semantic properties. No production dependencies were added or removed.

After the repair, Gradle returned **BUILD SUCCESSFUL**, with nine `UX128_SYSTEM_RENDER_PASS` lines and a final `UX128_SYSTEM_GALLERY_PASS screenshots=9`. This is native Compose Desktop QA with synthetic content, **not** physical Android validation.

Images visually inspected (all nine). Findings:

- **Phone RTL and bilingual:** section titles and primary statuses are legible at the selected default text scale. The current 10–12sp metadata looks small for field work and needs 200% scaling and native screen-reader tests. Secondary lines still reveal English icon *role codes*; those are QA annotations, not approved operator copy.
- **Concrete bidi defect:** original `03-phone-ar-light-type` displayed an isolated timestamp in visibly reversed order (`UTC 14:30 2026-11-22` instead of `2026-11-22 14:30 UTC`). We changed the specimen to U+2066/U+2069 LTR isolates around source codes/timestamps/currency runs and re-rendered both phone and Windows Arabic variants. The updated images visibly preserve `2026-11-22 14:30 UTC` and `2,500.75 EGP` as authored.
- **Status distinction:** queued/local/sync-confirmed/source-unavailable/operation-result are distinct in the visible text and supporting signal stripe; this is useful evidence for the semantic contract, but exact colors remain unreviewed.
- **High contrast:** the white border for *every* row is strongly delineated but visually loud; reject as the default style. Compare lower density focus/border hierarchy in a follow-up.
- **Windows:** the two-column state study is readable but leaves large white/dark whitespace. Do not treat this as a viable operational Windows screen, table or PM command center; actual source-anchored density experiments come next.
- **Icon study:** A/O/S are deliberate *class markers*, not assets. There has been no approval of custom rack, ODF, OTDR, tester or custody glyphs. The current large desktop icon space is not a real desktop inspector layout.

**Remaining quality gap:** No 200% dynamic type, Android OS Back/TalkBack, Windows keyboard and focus, actual role/source state adapter, measured contrast, performance, or real asset/site/operator acceptance. Keep PR Draft/unmerged.

Reproducible local PNG evidence files are under `apps/desktopApp/build/reports/ux128-system-specimens`. A SHA-256 evidence manifest should accompany the code/report; do not claim PNG binaries were uploaded to GitHub.
