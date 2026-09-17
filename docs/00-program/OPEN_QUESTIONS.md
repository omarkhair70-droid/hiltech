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

- Validate Kotlin Multiplatform/Compose Multiplatform against required Windows desktop, Android hardware, offline, camera/QR, background sync, printing/export, and future iOS needs.
- Validate whether desktop should be Compose native, browser-based, or hybrid for specific heavy finance/operations surfaces.
- Validate backend framework choice and current supported versions.
- Select local database, sync model, conflict-resolution model, API protocol, realtime transport, authentication, authorization, workflow engine, object storage, observability, CI/CD, hosting, backup and disaster-recovery model.
- Determine whether OpenFGA/Keycloak/Temporal-level infrastructure is justified at HILTECH scale or introduces unnecessary operational weight.

## Delivery

- What exact slice proves the architecture with the least fake data?
- Which real HILTECH project/site/warehouse subset should be the first pilot?
- What must exist before pilot data can be considered safe?
- Definition of freeze and definition of done per domain.
