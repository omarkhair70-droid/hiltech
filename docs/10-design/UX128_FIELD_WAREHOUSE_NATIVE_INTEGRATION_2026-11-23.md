# UX-128 — signed-in Field focus + Warehouse status language (2026-11-23)

**Owner lane:** Product / native UX. **PR:** #171. **Base:** Phase 7 S01–S06 combined #163, **not** the latest service-purchasing #181 or the future unified main. **Status:** Draft, not merged, not operator accepted.

## Implementation in real production Compose sources

- `AssignedWorkSection.kt`: on compact signed-in views, an explicit tap opens one currently assigned WorkOrder, rather than leaving the whole Today list above its details. The existing detailed callbacks for queued work, completion, evidence, personal-issued materials, asset lookup and engineering tests remain unchanged; all continue through their established server/queue and permission boundaries.
- `FieldSourcePresentation.kt` + `FieldSourcePresentationTest.kt`: a presentation-only focus admission predicate requires the current Today projection to contain that WorkOrder. If the assignment disappears, local focus closes; a later regrant never opens it automatically. The earlier selected-bundle identity/current-Today match still separately guards detail rendering.
- `HiltechShell.kt`: enables focus only with a nonblank verified signed-in identity and current organization key; the offline unverified read-only view keeps the baseline and never gains new actions. One existing shell vertical scroll remains the scroll owner.
- Android `MainActivity.kt`: a focused WorkOrder intercepts System Back and requests the same in-app return as the visible back button. Android-only system handling is not imported into common Kotlin UI. Desktop and wide layouts retain two-pane Today+details behavior.
- Returning to Today requests visibility of the **same WorkOrder card by ID**, not its previous row number; reorder must not route to another WorkOrder. No new nested scroll state or new backend/domain/permission path was added.
- `WarehousePhysicalOperatorSection.kt`: adopts shared operational semantic heading/status labels. Distinguishes a confirmed server receipt from a subsequent refresh failure, unauthorized handoff from a generic error, and absence of accessible warehouses. All Warehouse API operations and guards stay unchanged.

## Verification gates

- GitHub Actions workflow `.github/workflows/ux128-native-field-contract.yml` triggers the exact branch push and PR changes, runs the checked-in `FieldSourcePresentationTest`, compiles Windows shared/desktop, and assembles Android debug. Run **#45094575413** was QUEUED when this report was written. This is not proof of success yet.
- A successful Gradle compilation is **not** proof of System Back gesture, in-app anchor restoration, TalkBack, Android font scaling, field/warehouse operator experience, or server authorization. These need real-device/simulator and user-role evidence.
- Test in addition: removed assignment during focus, same-ID regrant, order reorder during focused detail, Back before a bundle loads, network read cache/offline read-only, role change, identity/org change, 200% Arabic text, desktop keyboard focus, screen reader.
- Current UX #171 is behind the newer Phase7 S07 service-purchasing owner branch #181 and parallel P8–P13 branches. **DevOps** owns exact final merged source SHA, Flyway collisions, shared host route integration, and release proof. Do not replace P7 V0063/V0064 or lift outdated vendor, acceptance, payroll or finance code into UX.
- All commercial, payroll, finance, maintenance and executive feature flags remain at their existing OFF settings. This change approves no payment, supplier send, or field operation.

## Next bounded product slices

1. Inspect CI and fix any exact-head compile/test defect before proposing review.
2. Test focused route against one real Android host/device and Windows shell with authenticated current-organization task data; add failure-state screenshot evidence and verify System Back.
3. Review warehouse scan → resource → permitted action → receipt/history with live source-bound DTOs and test stale scan/permission changes.
4. After DevOps integrates latest domain branches, mount and review P8 Finance, P9 payroll, P10 Sales, P11 Service and P13 Executive using their source-owned APIs and permissions. Keep business logic with source owners.
5. After all core modules through P13 are present, conduct final per-role and cross-platform design/a11y/performance pass. 

## 2026-11-23 — Actor isolation hardening

Warehouse operator selection, busy/error and visible warehouse directory are keyed by `organizationId + installationId + operatorScopeKey`; the physical workspace key also contains that scope. Both Android `MainActivity` and Windows `Main.kt` pass the actual signed-in `identityId`, so a role/account switch within the same organization cannot reuse the previous operator's remembered warehouse view. This only isolates UI state; the backend remains authoritative for every read and mutation.

**No CI green claim.** The current HEAD must pass the exact-head native workflow, and a real identity-switch / role-revocation check remains necessary.

## 2026-11-23 — Quick Scan source-linked drill-through

- `WarehouseQuickScanSection.kt` now invalidates the previous resolved AssetTag, asset search or stock search result as soon as the corresponding input changes or a fresh lookup begins. It also binds **every displayed result to the exact nonblank query actually submitted** to the server. A failed second lookup cannot leave the first item looking like the second.
- Source-connected `onOpenCanonicalAsset` / `onOpenCanonicalStock` actions appear only when the parent supplies a real destination. They route through `PhysicalOperatorWorkspace.loadAsset/loadStock`, not a parallel gallery, synthetic detail, or unchecked local inventory record.
- For an AssetTag, the source must provide a canonical `assetId`; an asset passport must actually belong to the selected warehouse before the detail opens. Server permissions and all existing mutation controls still revalidate independently.
- The existing shell remains the only vertical-scroll owner. After a successful canonical detail fetch, Compose `BringIntoViewRequester` moves toward that same asset or stock detail so the user does not have to hunt for it in the long workspace.
- The shell's scroll state is now keyed by current identity and current organization; a different signed-in actor or offline-only session cannot inherit the prior account's long-page scroll position.
- `WarehouseQuickScanQueryTest` adds negative tests for replaced codes, missing query, blank query and exact matching. This test is now part of the full shared native CI gate.

**Proof remains open:** These are source changes only until exact-head Gradle tests, Android APK, Windows compile and real actor/device checks pass. In particular verify scan → detail on Android, wrong-warehouse asset ID, revoked identity while the drill is open, stale selection on re-login and Back/scroll visibility on real devices.

## 2026-11-23 — Native shell workspace navigation

The existing signed-in `HiltechShell` previously stacked Identity/Sessions, People, Field, Warehouse and Projects in one long vertical page. It now exposes a compact horizontally scrollable Arabic-first **Workspaces** jump row near the signed-in identity heading. It opens exactly the existing source-bound Compose sections for `Today`, `Warehouse`, `Projects` and `People` via Compose `BringIntoViewRequester`; no new screen, duplicate state, API, permission or separate scroll owner.

- Each shortcut appears only when the matching source section state is present in the authenticated shell. This is availability to navigate, **never** authority to mutate; the server enforces permissions and role revocation.
- Actual sections have small, width-measured 1dp scroll anchors, so clicking a shortcut targets the start of the section rather than a huge multi-screen Column. Existing native back and focused WorkOrder logic is unchanged.
- Current identity/org scopes the shell scroll state; user and organization switches do not inherit previous operator scroll positions.
- This is a bounded navigation improvement, **not** a claim of final role-adaptive multi-pane information architecture or company-specific acceptance. Real Android TalkBack, font scaling, hardware Back and Windows pointer/keyboard smoke remain OPEN.
