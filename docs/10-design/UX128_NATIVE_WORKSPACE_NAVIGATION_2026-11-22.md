# HILTECH UX-128 — native application workspace navigation

**Date:** 2026-11-22
**Source branch:** `ux/128-sourcing-native-integration-20261122`
**Baseline:** PR #156, after the verified source-connected buyer workflow and shared Design System.
**Release state:** Draft; no production feature flags changed.

## What this implements

The real shared `HiltechShell` now supports an explicit **Overview / Procurement** workspace route. It is a real Compose interaction wired through the original `procurementOperator` mount from `apps/desktopApp/Main.kt`, not an inert design specimen or a parallel mock API.

- Overview preserves the existing People, Field, Warehouse, Project, Onboarding, Security and other content exactly as before.
- Procurement presents the existing **source-connected** purchase-requirements and supplier-sourcing work through the same server-based host callbacks, but as its own route instead of burying it between unrelated modules.
- The navigation appears **only** in a signed-in shell, with an active organization, when the desktop host has either `HILTECH_PROCUREMENT_REQUIREMENTS_ENABLED=true` or `HILTECH_SUPPLIER_SOURCING_UI_ENABLED=true`.
- There is **no client grant or new purchasing permission**. Backend role checks, feature flags, idempotency keys, restricted supplier document upload, canonical PO approval and receipt rules are unchanged.
- On mobile/older call sites, `procurementWorkspaceAvailable` has default `false`, preserving their legacy route and offline field behavior.
- Workspace selection is scoped by signed-in identity, current primary organization and feature state, so switching identity/org or disabling the feature returns to Overview. A stale requested Procurement route can never override the available routes.
- The shell's original scroll owner resets to top on workspace change.
- Leaving Procurement requires an explicit acknowledgment that unsubmitted quotation fields reset; a server mutation already in flight may still finish, so the signed-in user must refresh and verify authorized source before any retry.

## Design direction and limits

This is the **navigation architecture checkpoint**, not completion of the whole product's final visual identity. It is intentionally small to avoid inventing backend requirements/approval policies while the Engineering Phase 7 S04/S05 sources continue changing.

Next gates before merging this into Engineering:
1. Actual Windows authenticated buyer and non-buyer navigation with a company-owned organization; verify routes and 401/403 decisions and feature-flag combinations.
2. The unsent quote form behavior when switching workspaces, source refresh on reentry, and in-flight upload/record/approval confirmation with real server audit trail.
3. Keyboard navigation, explicit focus, assistive technologies, 200% text, Arabic RTL in workspace tabs, and desktop narrow-window behavior.
4. Expand role-specific Procurement journeys from existing Engineering contracts for technical reviewer, PO issuer, warehouse receiving and matching, without duplicating their authority.
5. Integrate company-wide typography, icons, motion and final semantic tokens. No new UI is production enabled until those acceptance gates are satisfied.

## Verification command

```powershell
.\gradlew.bat :shared:core:desktopTest --tests 'com.hiltech.shared.core.HiltechWorkspaceNavigationTest' :apps:desktopApp:compileKotlin --max-workers=2 --no-daemon --console=plain
```

Pure Kotlin tests check no navigation for SignedOut, default-off feature, missing company; both routes when host opt-in is valid; and fallback of a stale requested Procurement route. Native Windows compilation verifies that the true `HiltechShell` and desktop callback route compile. This does **not** prove actual clicking or production server permissions.

**Do not merge, deploy, turn on purchasing flags, or treat as operator accepted solely from these tests.**
