# HILTECH Product Completeness Matrix

Status: ACTIVE TRACKER

This file tracks capability families against the strict Definition of COMPLETE.

Legend:
D = Discovered
R = Researched
P = Product-defined
DM = Domain-defined
E = Experience-defined
T = Technically-defined
BR = Build-ready
I = Implemented
IN = Integrated
V = Verified
O = Operable
RL = Released
C = Complete

Current values are intentionally conservative.

| Capability Family | Current Level | Main blockers |
|---|---|---|
| Identity/Auth | R | auth spike, exact IdP, session/device rules |
| Authorization | P | real authority validation, OpenFGA spike |
| Executive Control | E | reality validation, queries/projections, visual prototype |
| Sales/Tenders | P | real tender flow, domain detail |
| Projects | P/DM | real project validation, transition table |
| Field Work | E | offline spike, site/device reality |
| Engineering | P | test equipment/drawing reality |
| People/HR | P | actual HR process/legal/retention |
| New Hire | E | actual onboarding requirements |
| Warehouse | P/DM | physical walkthrough, custody boundary, scan spike |
| Assets | P/DM | actual inventory, tagging, ledger schema |
| Procurement | P/DM | real documents/approval thresholds |
| Finance | P | accounting/bank reality |
| Payroll | P/DM | legal/accounting/bank reality |
| Client Experience | E | client reality, service/maintenance detail |
| Supplier | E | supplier workflow validation |
| Subcontractor | E | subcontractor reality |
| Security/CCTV | R/P | vendor inventory, permission/security design |
| Documents/Evidence | P | storage spike, retention/classification |
| Approval Engine | P/DM | policy reality, authz/version tech |
| Inbox/Notifications | P | event matrix/providers/preferences |
| Automation | P | exact policies, risk/test controls |
| Offline/Sync | T-model | spike/local schema/protocol |
| Integrations | R/P | real providers |
| Design System | R/P | visual tokens/components/prototypes |
| Mobile App Shell | E | nav + design + stack spike |
| Desktop App Shell | E | dense-data/packaging spike |
| Observability | T-model | provider/tooling + implementation |
| Infrastructure | T-model | provider/IaC/backup decisions |

## Rule
Never advance a family because documents "feel thorough".
Advance only when requirements of Definition of COMPLETE level are actually satisfied.
