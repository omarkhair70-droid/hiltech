# UX-02 / UX-03 — Role, Authority, Route & Global Navigation Experiment

**Checkpoint:** 2026-11-22  
**Status:** HYPOTHESES AND A REAL COMPOSE EXPERIMENT; not an approved product shell  
**Source:** checked main `c30fb707186cc0bbb38ba95b924737bd1bc56997`, Phase2 Inbox/Work Queue contract, `HiltechShell.kt`, Android `MainActivity`, Windows `Main`, existing Phase5 warehouse DTO and synthetic-native harness.  
**Execution:** Draft PR #134; active Engineering Phase6 S04 remains untouched.

## Why the navigation problem is larger than warehouse

The source `HiltechShell` renders SignedIn content in one `Column` with an outer `verticalScroll`: security/session information, available People, Field, Warehouse and Projects, plus onboarding and other related sections. The same shared core is used by Android and Windows. Existing sections have genuine source-bound callbacks, so the challenge is **not replacing commands**: it is preserving context as users move from authorized work to an authoritative object and back, without dragging unrelated information through one long screen.

The documents `INFORMATION_ARCHITECTURE.md`, `MOBILE_NAVIGATION_CANDIDATES.md` and `DESKTOP_NAVIGATION_CANDIDATES.md` already propose a single product with Home/Work/Explore/Inbox/Me and contextual workspaces, but none is frozen or validated across platforms/roles.

### Crucial permission boundary before an actual global nav rollout

**Visibility of a nullable `HiltechShellState.SignedIn` section is not sufficient evidence of a full navigation entitlement.** The backend/object API, organization membership, source action authority and current context must decide what may be accessed or acted upon. A fake `isOwner`, display-role-based allowlist, hardcoded global menu or unrestricted command search would be unsafe. **No global shell should be merged until a source-bound admissible destination projection is defined and tested.**

Current `HiltechNavigationStudy.kt` is a UI-only composition and navigation state machine. The **caller** must explicitly supply admitted destinations and existing source-backed Compose content. It does not implement permission checks and must not be described as evidence that an external permission model exists.

## Core route grammar — conceptual, not a backend API

Each route should carry at least:
- **Actor scope:** signed-in identity + organization (+ effective authorized context/role if relevant)
- **Source context:** Project, Site, WorkOrder, Asset, Stock Item, Employee, Approval etc., with exact object IDs; labels are not identifiers
- **Authorized destination:** current source-entitled area and/or object, rechecked on fresh load; loading/no-data is distinct from permission denial
- **Presentation:** desktop list/detail or compact mobile destination, not separate business state
- **Return path:** origin collection/filter, selected object and scroll anchor; invalidated if identity/organization/context or authority changes
- **Authoritative state:** version/timestamp/source; cached records cannot alone authorize a mutation

**Current experiment proves only area-level route/back and scope selection.** It does not store all deep object/filter/scroll state yet; those are explicit acceptance gaps, not invented.

## Source-reality task matrix

| Person | First real task and admissibility requirement | Navigational path to test | Current known gap/constraint |
|---|---|---|---|
| Mohamed / owner | A pending approval *actually assigned to him* by source authority | Work/Needs You → Approval source (PENDING, version/threshold) → approve/request change if authorized → return | Full owner Company Brain planned in Phase13; Phase2 Inbox approval foundation does not prove owner UI exists |
| Ahmed / finance | Authorized finance exception or review request (not assumed bank action) | Work → financial source/approval → maker-checker result → return | Finance actual objects + bank transport may be later phases; do not fabricate payroll/payment data |
| HR/admin | Missing document or offboarding access clearance in currently authorized employee context | People → Employee → onboarding/clearance source → back to filtered list | Shared People UI exists; sensitive data restrictions and revocation must stay backend-owned |
| Project Manager | A real assigned Project / Site / WorkOrder with blocker | My Projects → Site → Work → resource readiness/asset provenance → back to filtered project | Project Command Center exists; integrated multi-hop route and remembered filter not proven |
| Engineer | Assigned task + currently approved drawing/test version | Project/Site → WorkOrder → approved revision / measurement evidence → review status → back | Phase6 S04 engineering active; no UX edits or unapproved test/camera source |
| Storekeeper | Due return / calibration block and source-custody lookup | Warehouse Today → Asset Passport → current custodian/source history → return to same queue filter | Current experimental Today↔code lookup passed desktop headless pointer; object drill still below fold |
| Technician | Today's authorized assignment, connection can be absent | Today → WorkOrder → start/block/resume → evidence queued vs server confirmed → return | Phase6 stable contracts required; offline read-only must not imply server authority |

### Navigation invariants

1. **Denial by absence:** unknown/unauthorized destination is omitted, not displayed with a callback that bypasses the server. Missing projection can be loading, not synonymous with no permission.
2. **Revalidation:** when the admitted set changes, a now-invalid selected destination must not be rendered even for one frame. If no area remains, explain lack of currently available source work; never show stale protected content.
3. **Scope reset:** switching identity, organization **or object context** clears selection and history so the new scope does not inherit another worker's/project's screen.
4. **Return path:** back goes to last admitted area; if that source is revoked, choose only currently admitted destinations. Future object drill must carry selected filter and exact object source rather than arbitrary scroll.
5. **No false affordance:** `بحث بالكود` is an existing manual code-entry surface, not proof of physical camera scan. Inbox/notifications can only appear as navigable destinations when a real current-source UI and permission projection exist.
6. **Real content:** candidate area content is a composable slot over existing actual modules, not new mock cards, dummy API handlers, or copied enterprise UI.

## Two hypotheses for native Compose comparison

### A. CONTEXT_FIRST

Desktop: persistent admissible source destinations beside the selected content; contextual title and optional back stack. Compact: source-context title and accessible navigation strip above selected content. This resembles the semantic continuity in the existing IA documents.

**Strength:** learns once, makes cross-domain relation possible, can handle owner and PM object hopping.

**Risk:** global chrome may push urgent field work down; a horizontal strip can hide destinations and produce navigation discovery issues at 200% text.

### B. WORKBENCH_FIRST

First focus = chosen *admitted* operational area; other admitted destinations remain accessible through a secondary but explicit strip. On wide screens it does not require a permanent sidebar by default.

**Strength:** urgent task can be first on a field/warehouse phone; more usable on intermittent/brief sessions.

**Risk:** different role-specific first areas can feel like separate products, and repeated switching may be harder for PM or multi-role staff.

**Neither candidate is visually frozen.** Both must maintain the same source read models and callbacks in comparison.

## Actual implementation boundary — PR #134

Files:
- `shared/core/src/commonMain/kotlin/com/hiltech/shared/core/HiltechNavigationStudy.kt`: `HiltechNavigationCandidate`, immutable `HiltechNavigationTrace`, `hiltechNavigationSelect`, `hiltechNavigationBack`, `hiltechNavigationRevalidated` and the shared native Compose comparison surface.
- `apps/desktopApp/src/test/kotlin/com/hiltech/desktop/evidence/Phase5WarehouseRenderEvidence.kt`: A/B composition of **real Phase5 `WarehouseCommandCenterSection`** and **`WarehouseQuickScanControls`** from the same synthetic Warehouse DTO. Exact-head screenshot outputs `ux128-global-{context,workbench}-{phone,windows}.png` when build succeeds. The harness explicitly checks denied destination, return and post-revocation selection behavior.

The current actual Android/Windows `HiltechShell` remains the production default, and **no app-level global navigation is activated or claimed complete**.

## Gate list to move from study to integrated navigation

- [ ] Native exact-head desktop build + screenshot render of both candidates; inspect clipping and RTL, not only task exit status.
- [ ] Real pointer click to select authorized area and Back while current source content persists; test state retention/switching actor/context.
- [ ] Android exact-head assembly and emulator capture using current Compose app entry, matching supported source fixture.
- [ ] Server entitlement projection / actual source route contract validated with Engineering and security before exposing menu items.
- [ ] Existing source action callbacks integrated without re-encoding authorization logic or duplicating data.
- [ ] Keyboard focus, system Back, screen reader, 200% type, narrower Windows / tablet, reduced-motion and busy/denied/empty/offline variants.
- [ ] Operator observed tasks + result timings, real-device gates and reviewed design decision.
- [ ] Only after review: small coordinated production integration PR, feature flag and regression gating; no merge from this study PR without approval.

**Research judgment:** On the current evidence, global contextual continuity is a serious candidate for PM/owner/administration, while workbench-first is promising for warehouse/field. Whether a single hybrid grammar serves both remains unproven. `Industrial. Precise. Alive.` should be judged by correct source decisions and rapid recovery, not by fashionable UI styling.
