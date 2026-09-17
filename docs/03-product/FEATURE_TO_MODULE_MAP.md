# HILTECH Feature-to-Module Map

Status: FIRST PASS / NOT FROZEN

Purpose:
Connect feature families to owning server modules and primary client feature modules before code.

| Feature prefix | Primary server owner | Primary client area | Important dependencies |
|---|---|---|---|
| CORE-ID | identity / organizations | core/auth | people, permissions |
| CORE-NAV / CORE-ACT | inbox/search/audit + projections | core/navigation/search | all authorized objects |
| CTRL | read projections / domain queries | home/executive | projects, finance, people, warehouse |
| SALES | sales | sales | organizations, approvals, documents |
| WORK | projects + work | projects/work | people, warehouse, assets, engineering |
| FIELD | work | field | sync, documents, assets, warehouse |
| ENG | engineering | engineering | work, documents, assets |
| PEOPLE | people | people/selfservice | identity, organizations, assets, payroll |
| ONB | people | onboarding | identity, permissions, assets |
| WH | warehouse | warehouse | assets, procurement, projects |
| ASSET | assets | assets | warehouse, documents |
| PROC | procurement | procurement | suppliers/orgs, warehouse, approvals, finance |
| FIN | finance | finance | procurement, projects, approvals, integrations |
| PAY | payroll | payroll/selfservice | people, approvals, finance |
| CLIENT | clients/projections | clients | organizations, projects, finance, documents |
| SUPP | procurement + organizations | suppliers | documents, notifications |
| SUBC | work/projects + organizations | subcontractors | documents, warehouse |
| SEC | security | security | integrations, identity |
| DOC | documents | shared documents | object storage, all linking domains |
| APR | approvals | approvals | all subject modules, permissions |
| COMM | inbox + notifications | inbox | all event sources |
| AUTO | automation + domain jobs | hidden/system + contextual | domain modules |
| OFF | server sync endpoints + client sync | core/sync | all offline features |
| INT | integrations | usually contextual | external providers |
| OPS | observability/admin | admin/support | infrastructure |

## Rule
This map assigns primary ownership, not exclusive participation.

Example:
PAY-016 Payment Batch is payroll-owned business grouping, but finance owns authoritative bank execution/reconciliation.

## Freeze gate
Every BUILD_READY feature ID must eventually link to:
- server module,
- client module/surface,
- objects,
- commands/queries,
- events,
- permissions,
- offline classification,
- tests.
