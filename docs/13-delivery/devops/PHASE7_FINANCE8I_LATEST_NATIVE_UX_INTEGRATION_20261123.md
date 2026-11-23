# HILTECH — Phase7 + Finance8I + current native UX integrator gate

Date: 2026-11-23. Status: **DRAFT, NO-GO for main or rollout**.

## Source ownership and exact inputs

- Existing integrated Phase7 S01-S07 + Finance8A-I and canonical Evidence/Flyway V0001-V0066 in Draft PR #195, exact tested head d616a828d98951e637a5ace3f1c4d005765695e2. Its exact-head combined backend/Modulith/PostgreSQL/native and targeted Finance workflows passed (45096996355 / 45096996194).
- Later Phase7 source native host integration owner PR #181 head 35a301fab030e2a0fe89d3e5d64215f10312d364. This adds default-OFF Service Purchase navigation in Android and Windows and bounded private file-pickers; owner latest-head CI 45097489921 still pending during initial assembly.
- Later UX owner PR #171 head c290aaf20831b1b77d71051380d69bc2f7f72dc8. This updates the actual Android MainActivity.kt, Windows Main.kt, shared HiltechShell, WarehouseQuickScan, AssignedWork, Field presentations and visual system. Owner latest-head CI 45097910484 still pending at initial assembly.
- This isolated integrator branch uses *both* exact source merge commits, first Phase7 host af348eccd56d014dee10c20dd7ff34b2271fe326, then UX source fa02b0d1ad30059cb3ea7d1a74d159e7e1f9bfdd. Git auto-merged shared Android/Windows entrypoints without a textual conflict. Source owner files are kept, not cherry-picked from stale snapshots.

## Verified only by static inspection so far

- Both current Android and Windows Main entrypoints retain their dedicated Service Purchase host sections.
- Existing UX AssignedWork / source presentation / quick-scanner / role navigation changes remain in this branch.
- Finance migration series stays uniquely numbered through V0066 and source owner financial-action flags remain OFF.
- **No assertion is made** that an in-application user can navigate all workflows under real roles, that actual file selection/secure upload passed an authenticated device E2E, or that all new source combined tests are green.

## Exact-head integration gates

- New `.github/workflows/devops-phase7-finance8i-native-ux.yml` verifies the complete backend against disposable PostgreSQL, Flyway history and Spring Modulith and Android debug APK, Windows compile and full shared/native tests.
- UX-128 native focus workflow runs on this integration branch.
- Finance8I specialized service/Evidence review workflow runs here.
- If any test fails, diagnose and correct source-specific violations without relaxing security tests, approvals, document isolation or financial action flags. Push only after worktree diff/source SHA verification.
- User-facing service purchases are deliberately default OFF; do not enable them or expose finance/supplier messages without current company policy, role, real protected Evidence upload and staged native acceptance.
- When green, reconcile later Phase13A, Phase9, Phase10 and Phase11 **on downstream integrator branches**, with ordered Flyway allocations, current UX sources, fresh+upgrade DB checks, backup/restore and signed-in device tests.

No main commit, release, live finance action, warehouse mutation, supplier send, Docker lifecycle action or staging deployment is authorized by this checkpoint.