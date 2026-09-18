# HILTECH OS — Open Questions

Status: `RESEARCHING`

These questions must be answered before relevant areas are frozen.

## Company reality

- What are HILTECH’s current service lines in practice, not only on the website?
- Which project types are most frequent and which are most profitable?
- Which projects are direct contracts vs subcontracted work?
- What systems, spreadsheets, WhatsApp groups, paper processes, banking portals, CCTV/NVR systems, access-control systems, and accounting tools are currently used?
- What roles currently exist and who actually performs each responsibility?
- What approvals currently depend specifically on Mohamed or Ahmed?
- Which recurring operational failures or delays consume the most time?

## Mohamed / owner control

- What decisions require Mohamed today?
- Which decisions can safely be delegated or policy-automated?
- What information does Mohamed repeatedly request by phone/WhatsApp?
- What company health indicators would be genuinely useful daily, weekly, and monthly?
- Which confidential domains must remain visible only to owner-level roles?

## Ahmed / finance-admin

- What is Ahmed’s real responsibility boundary: accounting, payroll, treasury, admin, procurement, tax, HR, banking?
- How are salaries currently calculated and paid?
- Which bank(s) and corporate payment products are used?
- Is there current accounting software? If yes, does HILTECH replace, integrate, or coexist with it?
- What payment approval thresholds and maker/checker rules exist?
- What reconciliation and correction/reversal workflows are required?

## Employees / field

- How are attendance, overtime, assignments, expenses, advances, leave, PPE, tools, and site access handled now?
- Which field work must operate offline?
- What proof is required for completion: photo, signature, measurement, test report, location, supervisor approval?
- What hardware/software is already used for Fluke/OTDR/testing and can results be imported automatically?

## Warehouse / assets

- What asset categories and approximate quantities exist?
- Which items are individually tracked vs quantity stock?
- What is the actual value/risk profile of major tool classes?
- Is there one warehouse or multiple storage/site locations?
- Who currently has keys/access?
- What checkout/return practice exists today?
- Which tools require calibration, certification, repair, or warranty tracking?
- Is RFID/BLE/UWB/GPS justified for any asset classes, or are QR/barcodes sufficient initially?

## Projects

- What is the actual project lifecycle from lead/tender through handover and maintenance?
- How are BOQs, variation orders, materials, teams, testing, progress, subcontractors, and client approvals currently managed?
- Which data may clients see and which must remain internal?

## Clients

- What client types need access: owner, engineer, procurement, finance, facility manager?
- What should a client be able to approve or sign digitally?
- Which project evidence can be shared directly?
- What post-handover support/maintenance workflows exist?

## Security / facilities

- What CCTV/NVR brands and protocols are used at HILTECH HQ?
- What access-control hardware exists today?
- Are office/warehouse entry logs available through supported APIs or standards?
- What events should be visible remotely and to whom?

## Product / UX

- Exact role inventory and role combinations.
- Final information architecture.
- Complete mobile vs desktop responsibility boundaries.
- Bilingual Arabic/English typography and localization behavior.
- Motion language and performance budget.
- Accessibility requirements.

## Architecture / stack

Accepted/proven questions are no longer open:
- Kotlin Multiplatform + Compose Multiplatform is the accepted Android/Windows client direction.
- Room3/SQLite and typed offline command semantics are accepted.
- Compose Desktop dense-data feasibility is accepted.
- Kotlin/JVM + Spring Boot + Spring Modulith modular monolith is accepted.
- PostgreSQL + jOOQ is accepted for authoritative persistence/SQL access.
- Keycloak native OIDC and OpenFGA object/action authorization are accepted.
- S3-compatible binary evidence semantics and OpenTelemetry correlation contract are accepted.
- GitHub Actions is accepted as the CI/delivery control plane.
- Temporal, Redis, dedicated broker and dedicated search cluster are not baseline without a concrete trigger.

Still open before technical/final freeze:
- SPIKE-15 full cross-surface architectural proof and Ktor Client acceptance.
- exact HTTP wire conventions that SPIKE-15 is intended to lock.
- final Flyway baseline/version after exact schemas exist.
- production object-storage provider.
- infrastructure/hosting/runtime packaging.
- observability backend/collector/provider.
- Windows enterprise distribution/update operational choice and production signing.
- backup/disaster-recovery provider details and RPO/RTO.
- final CI action pinning/version re-check.
- future iOS timing and any iOS-specific spike when it becomes implementation scope.

## Delivery

- What exact slice proves the architecture with the least fake data?
- Which real HILTECH project/site/warehouse subset should be the first pilot?
- What must exist before pilot data can be considered safe?
- Definition of freeze and definition of done per domain.
