# HILTECH Client Architecture

Status: ARCHITECTURE MODEL v0.1 / KMP LEADING / NOT FROZEN

## Target Surfaces
- Android — first-class initial mobile target.
- Windows — first-class office/management desktop target.
- iOS — later from same shared architecture.
- Tablet/foldable — adaptive Android surface, not separate product.

---

# 1. Proposed Layering

```text
Platform App
  ├─ androidApp
  ├─ desktopApp
  └─ iosApp (later)
          │
          ▼
Feature UI / Presentation
          │
          ▼
Application / Use Cases
          │
          ▼
Domain
          │
          ▼
Data / Sync / API
     ┌────┴────┐
 Local DB    Network
```

Cross-cutting:
- identity/session,
- authorization hints,
- design system,
- navigation,
- telemetry,
- files/media,
- notifications,
- feature flags.

---

# 2. UI State Rule

UI should consume observable local/application state.

Avoid:
- screen directly calling HTTP and rendering response,
- maintaining one state in socket layer and another in database,
- business rules inside composables.

Preferred:
UI -> ViewModel/Presenter -> Use Case -> Repository -> Local/Network.

---

# 3. Local Database

Room/SQLite candidate.

Local DB can store:
- assigned projects/sites/work,
- cached objects,
- local drafts,
- sync queue,
- document/evidence metadata,
- upload queue,
- recent search/context,
- notification/inbox cache where useful.

Sensitive fields must be classified before caching.

---

# 4. Repository Pattern

Repository abstracts local/network convergence per domain.

Examples:
- ProjectRepository
- WorkRepository
- AssetRepository
- PayrollReadRepository
- InboxRepository

Do not create one giant GenericRepository.

---

# 5. Commands

Offline-capable actions create typed client commands.

Example:
```text
CompleteWorkOrder(
  operationId,
  workOrderId,
  baseVersion,
  evidenceIds,
  materialUsage,
  completedAt
)
```

Online-only actions:
call authoritative command endpoint and only show final result after server confirmation.

---

# 6. Feature Modules

Candidate grouping:

```text
features/
  home/
  work/
  projects/
  field/
  engineering/
  warehouse/
  assets/
  procurement/
  finance/
  payroll/
  people/
  onboarding/
  sales/
  clients/
  suppliers/
  subcontractors/
  approvals/
  inbox/
  security/
  search/
  selfservice/
```

These are code ownership modules, not separate apps.

---

# 7. Shared Core Modules

Candidate:

```text
core/
  model/
  network/
  database/
  sync/
  auth/
  permissions/
  files/
  notifications/
  telemetry/
  time/
  validation/
  ui/
  design/
  testing/
```

Avoid "core" becoming a dumping ground.
Every shared module needs explicit purpose.

---

# 8. Platform-Specific Implementations

Android:
- CameraX/scanning.
- WorkManager.
- notifications.
- biometric/credential APIs.
- file/media handling.
- location when allowed.

Desktop:
- window lifecycle.
- file picker.
- printing/export.
- keyboard shortcuts.
- tray/notifications if useful.
- auto-update integration.
- code-signing/distribution.

iOS later:
- equivalent platform adapters.

Shared interfaces isolate platform differences.

---

# 9. Navigation

Do not encode role as separate navigation graph.

One navigation/object model; allowed destinations/actions depend on:
- capability,
- permission,
- current context.

Deep link:
notification/search -> object/action.

---

# 10. Adaptive Layout

Shared screen model can render:
- single pane,
- list/detail,
- list/detail/supporting pane.

Phone:
one primary pane.

Tablet:
two/three panes where useful.

Desktop:
dense panes/inspector.

---

# 11. Offline Indicator

Sync state is first-class UI state.

Every offline-capable mutation can expose:
- local,
- queued,
- syncing,
- synced,
- failed,
- conflict.

---

# 12. Design System Boundary

Shared:
- colors/tokens,
- type scale,
- spacing,
- shapes,
- status semantics,
- common controls,
- motion tokens,
- icons.

Platform/surface can adapt:
- density,
- navigation,
- pointer/keyboard behavior,
- touch size,
- multi-pane structure.

---

# 13. Error Model

Avoid raw HTTP error handling in UI.

Map to product errors:
- Offline
- Unauthorized
- PermissionDenied
- VersionConflict
- Validation
- IntegrationUnavailable
- RetryableServerError
- PermanentFailure
- ReauthRequired

---

# 14. Testing

Client test layers:
- domain/use-case unit tests,
- repository tests,
- sync/conflict tests,
- local DB migration tests,
- UI state tests,
- Compose UI tests,
- platform integration tests,
- Android offline/background tests,
- desktop keyboard/table tests,
- RTL/adaptive tests.

---

# 15. Spike Gate

Before freeze prove:
- Android + Windows shared feature.
- Room shared DB.
- sync queue.
- QR capture.
- Arabic RTL.
- dense desktop list.
- Keycloak OIDC.
- large file/photo upload.
- Windows packaging/update path.

## Completion gate
Only after spikes + module boundaries + dependency graph + package structure are confirmed.
