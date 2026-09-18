# HILTECH OS — Product Constitution

Status: `VALIDATED` for principles, not for implementation details.

These principles govern product and architecture decisions unless explicitly reopened with evidence.

## 1. One HILTECH

HILTECH is one authenticated product and one source of truth.

Do not create separate disconnected products for owner, finance, technician, warehouse, client, supplier, or NOC merely because their experiences differ.

Different users receive different surfaces, permissions, priorities, and workflows inside the same HILTECH system.

## 2. Role-aware, context-aware experience

The product adapts to:

- who the person is,
- their organization,
- role and permissions,
- current project/site/context,
- device form factor,
- operational state.

The owner should not receive field-worker noise. A technician should not see confidential finance. A client should understand that HILTECH is a large connected system while only seeing what belongs to the client relationship.

## 3. Design the whole company; ship complete slices

We design complete product surfaces and complete domain behavior before implementation.

We do not mark a feature complete because one screen exists. A product capability is complete only when its normal, error, permission, history, integration, audit, offline/online, and lifecycle behavior has been defined and tested as applicable.

Implementation may be incremental, but slices must be end-to-end and operationally coherent.

## 4. Enter once, flow everywhere

Data should be entered at the point where reality occurs and then flow to every legitimate downstream consumer.

Examples:

- approved field overtime can influence payroll without retyping,
- consumed material can affect warehouse stock and project cost,
- completing field work can update project progress and client-visible milestones,
- receiving a supplier delivery can update inventory, procurement state, and finance readiness.

Avoid duplicate manual entry.

## 5. Management by exception

The system should remove routine administration from Mohamed and other managers.

Management should primarily see:

- exceptions,
- decisions requiring authority,
- risks,
- material changes,
- important approvals.

The goal is not faster bureaucracy. The goal is less bureaucracy.

## 6. Physical equals digital

HILTECH operates in the physical world. Important physical entities must have digital identities and histories.

Examples:

- tools and expensive equipment,
- warehouse stock,
- racks and network assets,
- project sites,
- vehicles if applicable,
- doors/access events,
- cameras/security systems,
- customer infrastructure.

## 7. HILTECH removes work

Digitizing a paper form is not automatically success.

For every workflow ask:

> Can the system eliminate this manual step entirely by deriving the data from work that already happened?

## 8. One event, many legitimate consequences

A real operational event may update multiple domains. The event should happen once and propagate through rules, workflow, and permissions.

## 9. Auditability by default

For sensitive or operationally significant actions, the system should be able to answer:

- who,
- did what,
- when,
- from which device/context where appropriate,
- to which object,
- previous state,
- resulting state.

This is particularly important for money, warehouse/assets, permissions, security, approvals, and client commitments.

## 10. Offline is a product concern, not an implementation patch

Field operations may happen with poor or unavailable connectivity. Offline/read/sync behavior must be deliberately designed before implementation.

Not every operation is allowed offline. High-risk financial/security actions may require online authority.

## 11. Desktop and mobile are two windows into one live system

Desktop is not a separate back-office product and mobile is not a remote-control shell.

Both connect to the same business state and account identity. Changes legitimately made on one surface are visible on the other according to permissions and synchronization rules.

Each form factor should have an experience appropriate to the device rather than stretching the same layout.

## 12. Enterprise software is allowed to feel alive

HILTECH must not become a dead collection of gray forms and tables.

Motion, typography, iconography, interaction, haptics, data visualization, and transitions are first-class design concerns.

Motion should communicate state, continuity, hierarchy, system activity, and physical/digital movement rather than exist as decoration.

## 13. Product truth before code

Chats are not the source of truth. This repository is.

Research, decisions, product maps, workflows, stack choices, and architecture must be reflected here before they are considered part of the project.

## 14. Freeze deliberately

No architecture or technology choice is frozen because it was previously suggested. It becomes frozen only after research and explicit validation.

After freeze, foundational changes require evidence and an ADR/reopen decision rather than casual drift.


## 15. Scale without redesign

HILTECH OS is not designed around today's headcount, number of engineers, technicians, projects, warehouses, clients, partners, or branches.

Current company size is operating data, not an architectural limit.

The system must support growth through configuration and data:
- new employees join through the same identity/onboarding/role/team model,
- new teams and reporting structures can be created without code changes,
- new projects/sites/work types/storage locations/assets can be added without schema redesign,
- new branches/warehouses/project stores can be represented through the same location/storage model,
- new clients/suppliers/subcontractors/partners use the same organization/relationship model,
- increased volume should require capacity/operational scaling, not a product rewrite.

Do not simplify a core domain merely because today's population is small.
Do not over-engineer today's UX for hypothetical scale either.

Design the model to scale; tune the initial experience to current reality.
