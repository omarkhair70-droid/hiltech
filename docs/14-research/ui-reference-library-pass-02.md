# UI / UX Reference Library — Pass 02

Status: RESEARCHING
Research date: 2026-09-18

Purpose: deepen the reference base in the areas not sufficiently covered by Pass 01: finance/payroll, approvals, client/owner visibility, command/search, network/NOC interfaces, dense desktop data, Arabic/English, motion, and adaptive layouts.

---

## 1. Gusto — Payroll Approval Integrity

Source:
- https://support.gusto.com/article/240829150046240/set-up-approvals-for-payroll-for-admins

Observed patterns:
- Payroll approval can be explicitly requested before submission.
- Approval creates a review task for an authorized admin.
- While approval is active, payroll is locked from editing.
- Rejection returns work to the preparer.
- Activity logs remain visible.
- Approval roles can differ between edit-only and full-access admins.

HILTECH implications:
- PAY-014 Payroll Versioning and APR-011 Version Binding are mandatory, not optional polish.
- A payroll run entering approval must become immutable for that version.
- Changing payroll after review must create/supersede version, not silently preserve approval.
- Ahmed can prepare; Mohamed/authorized approver can approve without taking ownership of preparation.
- Payroll history needs an explicit activity/audit view.

Do not copy:
- Gusto payroll taxonomy or US-specific payroll concepts.
- its admin-role model without HILTECH authority validation.

---

## 2. SAP Ariba / SAP Concur — Approval Flow Visibility

Sources:
- https://help.sap.com/docs/buying-invoicing/approval-process-management-guide/6b706976c1da1014a10fd76c24c4e631.html
- https://help.sap.com/docs/buying-invoicing/sap-ariba-procurement-mobile-app-guide/managing-tasks-viewing-approval-flows-viewing-comments-and-nudging-approvers-in-sap-ariba-procurement-mobile-app
- https://help.sap.com/docs/sap-concur/mobile-app-for-concur-expense-iphone-user-guide/submit-expense-report-with-approval-flow-screen

Observed patterns:
- Approval rules are condition-driven.
- Approval flow can show active approvers/reviewers and history.
- Mobile users can inspect approval details, comments, current flow and send reminders.
- Expense approval can expose/modify an approval flow under configured policy.

HILTECH implications:
- Approval request UI should show the flow, not only current status.
- Users need visibility into "who currently has the ball".
- Comments/history belong to approval context.
- Policy may generate one or multiple approvers.
- Reminder/escalation should be system-controlled, not WhatsApp chasing.

Important HILTECH rule:
A normal user should not be allowed to arbitrarily rewrite approval policy. Visibility of flow does not imply authority to change it.

---

## 3. Autodesk Construction Cloud / Forma — Owner & Project Visibility

Sources:
- https://construction.autodesk.com/tools/dashboards-and-data-analytics/
- https://www.procore.com/press/procore-introduces-new-suite-of-portfolio-management-and-capital-planning-capabilities-for-owners-powered-by-procore-ai
- https://www.autodesk.com/blogs/construction/?p=20962
- https://www.autodesk.com/support/technical/article/caas/sfdcarticles/sfdcarticles/Showing-section-ownership-and-progress-on-the-Forms-list-in-Autodesk-Construction-Cloud-Build.html

Observed patterns:
- Owner views aggregate project/company-level performance.
- Project Home emphasizes actionable status, risks and drill-down.
- Approval workflows increasingly include collaborators/reviewers with supporting documents.
- A clear "ball-in-court" owner/status indicator is important at scale; lack of it forces users to open every record.

HILTECH implications:
- CTRL-004 Project Portfolio Health should expose ownership and next action, not only status color.
- Project lists must reveal: owner, state, risk, blocking party, next milestone.
- Approval/work lists need visible "waiting on whom" fields.
- Mohamed/PM should not have to open 20 records to know who is blocking a project.

Do not copy:
- owner capital-planning concepts before HILTECH needs them.
- dashboard widgets without direct action paths.

---

## 4. Linear — Search & Contextual Command

Sources:
- https://linear.app/docs/search
- https://linear.app/changelog/2019-12-18-new-command-menu
- https://linear.app/changelog/2019-10-07-contextual-command-menu

Observed patterns:
- Global search has a dedicated shortcut and supports multiple object types.
- Search supports exact identifiers as well as text.
- Recent items are part of the retrieval model.
- Command menu prioritizes actions based on current context/selection.
- Mouse-invoked command menus can appear near the object while retaining keyboard searchability.

HILTECH implications:
- CORE-NAV-004 Global Search and CORE-NAV-005 Command Palette are separate but connected concepts.
- Exact IDs like project number, asset tag, PO, invoice, employee should open instantly.
- Contextual command menu should offer only actions valid for the selected object and permission.
- Recent Context should be first-class.
- Desktop power users can become dramatically faster through keyboard-first command access.

Do not copy:
- Linear's software-development terminology or ultra-minimal visual density.

---

## 5. Slack Enterprise Search — Cross-Object Search Philosophy

Sources:
- https://slack.com/features/enterprise-search
- https://slack.com/help/articles/38693462131219-Search-across-your-applications-with-enterprise-search

Observed patterns:
- Search can span multiple underlying sources while presenting one entry point.
- Connected source availability remains permission/admin controlled.
- Natural-language answers can be layered above search, but underlying access still depends on connected sources.

HILTECH implications:
- Search should feel global even if data is owned by many modules.
- AI search later should not bypass permissions.
- Exact object search remains important even if semantic/AI search exists.
- Search result grouping can separate Projects, People, Assets, POs, Invoices, Documents.

---

## 6. Datadog / Grafana — NOC, Topology & Relationship Views

Sources:
- https://docs.datadoghq.com/network_monitoring/devices/topology/
- https://grafana.com/docs/plugins/yesoreyeram-infinity-datasource/latest/advanced-features/node-graph/

Observed patterns:
- Device topology is interactive, relationship-driven, filterable and grouped.
- Network graphs distinguish nodes and edges explicitly.
- Filters/grouping by location/vendor/device type improve navigability.
- Topology is useful for understanding upstream/downstream impact, not decoration.

HILTECH implications:
- Future NOC / site-network visualization should model actual node/edge relationships.
- Group/filter by site, room, device class, vendor, health.
- Impact view can show "what downstream assets/sites are affected".
- Physical infrastructure graph and network topology may be related but not identical models.
- Motion can visualize state/flow, but graph must remain readable when motion is reduced.

Do not copy:
- telemetry-first overload for users who only need project/site status.

---

## 7. TanStack Table — Dense Desktop Interaction Patterns

Sources:
- https://tanstack.com/table/latest/docs/guide/features
- https://tanstack.com/table/v8/docs/guide/column-pinning
- https://tanstack.com/table/latest/docs/reference/static-functions/functions/column_pin
- https://tanstack.com/table/v8/docs/framework/react/examples/virtualized-columns

Observed patterns:
- Mature data-grid experiences need sorting, filtering, grouping, pinning, visibility, selection, resizing, pagination/virtualization.
- Newer APIs use logical start/end terminology, important for RTL support.
- Dense data sets need virtualization rather than rendering every row/column.
- Table state can be persisted/customized.

HILTECH implications:
- Desktop finance/warehouse/project lists need a real table interaction model, not static HTML-like tables.
- Column pinning must be direction-aware for Arabic/English.
- User-saved views/column sets are worth designing later.
- Bulk actions need explicit selection state.
- Mobile should not inherit these grids; use object/action layouts instead.

Important:
Technology choice is not frozen by this research. TanStack is a pattern/reference even if desktop client remains Compose.

---

## 8. Arabic / English — W3C + IBM Plex

Sources:
- https://www.w3.org/International/alreq/
- https://www.w3.org/TR/alreq/
- https://www.ibm.com/design/language/typography/typeface/

Observed requirements:
- Arabic base layout is RTL.
- Numbers and embedded Latin text remain LTR within bidi text.
- Layout, not only text alignment, must respect RTL.
- Mixed Arabic/English/IDs/numbers require correct bidi handling.
- IBM Plex provides Arabic alongside Latin and is explicitly designed as a multilingual family.

HILTECH implications:
- RTL is architecture/design-system work, not translation work.
- Logical start/end concepts must be used wherever possible.
- IDs, IPs, serials, monetary values and timestamps require deliberate bidi testing.
- Tables need RTL-aware column pinning and number alignment.
- Typography research should compare families with real HILTECH mixed-content screens, not isolated Arabic paragraphs.
- IBM Plex Sans Arabic becomes a serious candidate, not a frozen choice.

---

## 9. Android Adaptive — Current 2026 Platform Direction

Sources:
- https://developer.android.com/develop/adaptive-apps/guides/list-detail
- https://developer.android.com/develop/adaptive-apps/guides/support-different-display-sizes
- https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive
- https://developer.android.com/guide/navigation/navigation-3/recipes/material-listdetail

Observed patterns:
- List/detail layouts can expand from one pane to two or three panes based on available window size.
- Adaptive design can replace layout structures, not merely stretch components.
- Material3 Adaptive 1.3 introduced Navigation3 integration and includes RTL-related adaptive improvements.
- Supporting panes and extra panes are first-class patterns for wide screens.

HILTECH implications:
- Tablet/large Android devices can approach desktop-like workflows without creating a separate tablet product.
- Project list -> detail -> supporting activity/inspector is a canonical HILTECH pattern.
- Warehouse inventory -> selected asset -> movement/history can become multi-pane.
- Desktop/mobile IA can share object grammar while rendering different pane counts.
- RTL adaptive behavior must be tested from the start.

---

## 10. Motion — Compose State Animation

Source:
- https://developer.android.com/develop/ui/compose/animation/quick-guide

Observed patterns:
- Compose supports explicit state-driven animation.
- Visibility changes should be modeled semantically; alpha-only hiding can still leave content in accessibility composition.
- Draw-phase animations can be more efficient than layout/recomposition-heavy animation.

HILTECH implications:
- Motion must map to state transitions, not decoration.
- Accessibility semantics must change with visual state.
- Performance budgets matter for dense enterprise screens.
- Prefer state-driven transitions:
  - PENDING -> APPROVED
  - WAREHOUSE -> CHECKED_OUT
  - SYNCING -> SYNCED
  - HEALTHY -> DEGRADED
- Avoid permanent ambient animation in high-density screens.

---

# Cross-Reference Conclusions — Pass 02

## Pattern F — High-trust approval requires visible process
Approval is not a button. It has:
- exact subject/version,
- current approver,
- approval path,
- history,
- comments,
- consequences.

## Pattern G — "Who owns the next action?" is critical
Project/approval lists should surface ball-in-court ownership.

## Pattern H — Search becomes navigation at scale
Hierarchy alone will not survive HILTECH's object count.
Exact IDs + recent context + command/search are core IA.

## Pattern I — NOC/topology is relationship visualization
Nodes/edges/impact, not a decorative network background.

## Pattern J — Dense desktop needs power tools
Pinning, grouping, filters, selection, saved views, virtualization, keyboard access.

## Pattern K — Arabic must shape architecture
RTL, bidi, numbers, logical start/end and adaptive panes need explicit testing.

## Pattern L — Motion is state grammar
Movement should explain changed state, direction, ownership or flow.

---

# Decisions Strengthened by Pass 02

1. One shared Approval Engine.
2. Approval bound to object version.
3. "Waiting on" / next-owner must be visible in high-volume lists.
4. Global Search and Command Palette are core desktop features.
5. Mobile and desktop share objects but not density/layout.
6. Arabic/RTL must be first-class from foundation.
7. Multi-pane adaptive layout should be a platform capability.
8. NOC/network view should use real topology data when implemented.
9. Motion system must include accessibility/performance rules.
10. Data-table capability deserves its own design/architecture track.

---

# Still Not Frozen

- exact font family
- exact nav component
- exact table/grid implementation
- exact command-menu interaction
- exact dashboard composition
- exact motion timings/easing
- exact NOC topology library
- exact desktop UI technology
- final language default and Arabic/English switching policy
