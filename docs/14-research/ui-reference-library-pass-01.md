# UI / UX Reference Library — Pass 01

Status: RESEARCHING
Research date: 2026-09-18

Purpose: collect current product patterns relevant to HILTECH before any visual design freeze.

This is not a style moodboard and not a list of products to copy. Every reference is evaluated for a specific HILTECH problem.

---

## 1. Procore — Field / Drawings / Permissions / Offline

Sources:
- https://support.procore.com/procore-mobile-android/user-guide/drawings-android
- https://support.procore.com/procore-mobile-android/user-guide/drawings-android/tutorials/sync-and-download-drawings-android
- https://support.procore.com/procore-mobile-android/user-guide/drawings-android/permissions

Observed patterns:
- Field drawings are cached/downloaded for offline use.
- Offline actions can sync when connection returns.
- Permissions are action-specific, not just "can open project".
- Drawings are contextual objects rather than loose files.
- Current revision matters strongly in field work.

Potential HILTECH adoption:
- FIELD-006 Offline job bundle.
- ENG-001 / ENG-002 latest-approved drawing and revision awareness.
- permission-by-action rather than only role.
- explicit download/sync/freshness state.

Do not copy:
- construction-specific navigation hierarchy blindly.
- generic drawing-centric workflow for every HILTECH project.

Research question:
Should HILTECH auto-prefetch assigned job documents instead of requiring technicians to manually download disciplines?

---

## 2. Hilti ON!Track — Warehouse / Scan / Asset Transfer

Sources:
- https://help.ontrack3.hilti.com/hc/en-us/articles/34399903185681-How-to-navigate-in-the-mobile-app
- https://help.ontrack3.hilti.com/hc/en-us/articles/34399854140817-How-to-transfer-assets-on-the-mobile-app

Observed patterns:
- Mobile home exposes direct operational actions such as Transfer, Inventory, Identify, Nearby, Repair/Return and Field Request.
- Asset transfer can target a location, storage asset, or worker.
- Scanning/identification is a first-class mobile action.
- Transfer is a cart/workflow, not a silent edit of "current owner".

Potential HILTECH adoption:
- scan-first warehouse mobile experience.
- worker/site/storage as explicit custody destinations.
- transfer batch/cart for multiple assets.
- inventory/count and nearby-location tooling as separate modes.

Do not copy:
- a 3x3 launcher grid as HILTECH's final home.
- Hilti-specific repair/service flows unless relevant.

Research question:
Can HILTECH make scan/context even faster by starting from active Project/Site/Worker context?

---

## 3. Samsara — Physical Assets + Operations Visibility

Sources:
- https://www.samsara.com/products/equipment-tracking
- https://www.samsara.com/products/equipment-tracking/asset-tag
- https://kb.samsara.com/hc/en-us/articles/27961170140557-Bluetooth-Location-Tracking-for-Asset-Tags

Observed patterns:
- One platform connects people, equipment and operational data.
- Asset location is shown as observed/detected location with history rather than pretending perfect certainty.
- Small high-value equipment can use tags and nearby gateways/mobile devices.
- Missing/recovery workflows exist separately from normal location.

Potential HILTECH adoption:
- physical/digital asset identity.
- last-detected semantics instead of false real-time certainty.
- optional BLE/UWB/tag layer for expensive tools later.
- warehouse/site gateways as future integration.
- missing asset as explicit incident workflow.

Do not copy:
- fleet-centric IA if HILTECH's fleet does not justify it.
- hardware dependency in V1.

Research question:
Which HILTECH assets are valuable enough to justify active tags vs QR-only?

---

## 4. ServiceNow Mobile / Field Service — Persona Optimization + Offline

Sources:
- https://horizon.servicenow.com/native-mobile/overview
- https://www.servicenow.com/docs/r/field-service-management/mobile-experience-fsm.html
- https://www.servicenow.com/docs/r/field-service-management/work-order-management/complete-work-mobile.html

Observed patterns:
- ServiceNow uses purpose-built experiences for employees vs field/agent personas on top of the same platform.
- Native mobile workflows use camera/GPS/Bluetooth/voice.
- Field users can plan, execute and complete work offline, then sync.
- Managers have mobile control over assignments and SLA risk.

Potential HILTECH adoption:
- one shared HILTECH product/data model but strongly role-shaped experiences.
- mobile native capabilities should be first-class, not web wrappers.
- PM/supervisor mobile should allow operational control, not just read dashboards.
- explicit offline record/update model.

Important HILTECH difference:
HILTECH currently intends one branded app rather than separate employee/agent store apps. Persona-specific experiences live inside one shell unless future evidence proves separation necessary.

---

## 5. Rippling — Employee Lifecycle as One Source of Truth

Source:
- https://www.rippling.com/en-GB/global-payroll-and-hiring-2

Observed patterns:
- Hiring/onboarding connects employee identity to devices, training and payroll.
- Employee setup is treated as an orchestrated lifecycle, not a standalone HR form.

Potential HILTECH adoption:
- role-aware onboarding orchestration.
- employee record should drive permissions, project access, assets and payroll eligibility.
- avoid re-entering identity/employee facts across modules.

Do not copy:
- consumerized "app launcher" model if it fragments HILTECH into mini-apps.

---

## 6. Datadog Mobile — Executive/On-call Compression

Sources:
- https://docs.datadoghq.com/mobile/
- https://docs.datadoghq.com/mobile/widgets/
- https://www.datadoghq.com/blog/mobile-incident-management-datadog/

Observed patterns:
- Mobile prioritizes incidents, on-call, alerts and drill-down instead of exposing full desktop authoring.
- Severity and ownership are visible immediately.
- Notification center preserves push context.
- Mobile can declare/escalate incidents while deeper dashboard editing stays on desktop/web.
- Home-screen widgets expose only critical operational health.

Potential HILTECH adoption:
- Mohamed mobile = exceptions/critical state, not the entire desktop.
- severity + owner + time + impact on critical cards.
- Inbox retains notification context.
- desktop may author/configure deeper reports while mobile acts/triages.
- future Android widgets for critical company/project health are worth researching, not V1 requirement.

Do not copy:
- telemetry-heavy dashboard density for normal business users.

---

## 7. Linear — Interface Coherence Under Feature Growth

Source:
- https://linear.app/now/behind-the-latest-design-refresh

Observed lesson:
Products often become cluttered one useful feature at a time. Consistency of location bars, actions and hierarchy must be actively maintained as the product expands.

Potential HILTECH adoption:
- strong object-detail grammar.
- predictable placement of global vs contextual actions.
- feature additions must pass IA consistency review.
- periodically prune duplicated controls rather than endlessly adding.

Do not copy:
- software-development-specific visual language.
- ultra-minimal density if it hides operational context.

---

# Cross-reference conclusions — Pass 01

## Pattern A — Mobile is not Desktop miniaturized
Supported across ServiceNow, Datadog, Hilti, Procore.

HILTECH implication:
Mobile prioritizes action, scan, capture, approval, incident, and context.
Desktop prioritizes density, comparison, configuration, bulk work, analysis.

## Pattern B — Offline must be object/workflow-aware
Procore and ServiceNow both reinforce that field/offline behavior must be designed around downloaded/cached work and later synchronization.

HILTECH implication:
Do not bolt on a generic cache after implementation.

## Pattern C — Physical custody is a workflow
Hilti and Samsara reinforce explicit transfer/location/custody/history.

HILTECH implication:
Asset current location/user should not be a casually editable field.

## Pattern D — One truth can still produce persona-specific experiences
ServiceNow separates mobile experiences by persona; Rippling connects multiple employee functions to a single employee truth.

HILTECH hypothesis:
Keep one branded app, but make role experiences deeply different inside it.

## Pattern E — Critical mobile interfaces compress
Datadog shows the value of putting urgent incidents/actions on mobile while leaving deep authoring elsewhere.

HILTECH implication:
Mohamed/manager mobile should be "Needs You", not 30 executive dashboards.

---

# Anti-patterns discovered

1. Mini-app launcher architecture where users must understand internal department/module structure.
2. Desktop table layouts merely shrunk to mobile.
3. Asset location presented with false precision.
4. Offline mode hidden from the user.
5. One broad "Admin" permission.
6. Push notification as the only record of an important event.
7. Generic dashboards for all roles.
8. Files without business-object context.
9. Feature growth that moves actions unpredictably.
10. Mobile read-only management experience.

---

# Research Pass 02 — Required

Need deeper references for:
- finance/payroll desktop interaction,
- high-value approval UX,
- client portals,
- command/search patterns,
- industrial control/NOC topology,
- bilingual Arabic/English enterprise typography,
- motion systems for operational state,
- dense desktop data tables,
- accessible charts and status colors,
- Android adaptive large-screen patterns.

No visual direction is frozen after Pass 01.
