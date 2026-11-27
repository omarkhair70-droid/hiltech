# Native product verification

Date: 2026-11-25

Status: active engineering contract; acceptance remains open

## Source and publication

PR #242 is the reviewed integrated candidate, including the fast-forwarded
PR #243 source. Always fetch and inspect its current head. Keep new work on
short-lived review branches based on this candidate. Preserve dirty files,
local history and evidence in other checkouts; do not treat source consolidation
as release acceptance.

This repository is public. Detailed internal audits, company-specific matrices,
role assignments, deployment evidence and the canonical closeout register remain
private. Do not commit copies, private links, secrets or raw business evidence.
Use synthetic fixtures for public tests. A public PR may describe a technical
defect, its fix and the scope of validation without reproducing private findings.

## One journey, explicit evidence

For each bounded repair, connect the source requirement, entry route, controls,
form fields, permissions, command, response and resulting source record. Review
Windows and Android separately, including Arabic and English, narrow and wide
layouts, keyboard/focus behaviour and errors. Shared code does not prove that
both native hosts mount or execute the interaction.

Use these evidence states independently:

| Evidence | Meaning |
| --- | --- |
| Implemented | The inspected source contains the change. |
| Test passed | The named test passed against recorded source and configuration. |
| Test failed | The attempted test failed; preserve the failure and its scope. |
| Not tested | No successful attempt exists, including inaccessible environments. |
| Decision required | A specific unresolved policy decision prevents that part. |
| Journey accepted | The complete role journey passed on the intended device and server. |

Record the actual commit or source hashes, build/runtime, actor scope, test
fixture, command result and limitations. Compilation, a screenshot, a source
inventory and a green CI job are useful evidence of different things; none is
automatically complete product acceptance. A render fixture must return a
failure when capture fails. Registry publication is not deployment.

## Interaction and authority contracts

- Dates use date controls and preserve date-only API values across time zones.
- Record references use source-backed choices. Labels do not become authority.
- Invalid numeric input produces a field error, never an implicit zero or null.
- Preserve an editing draft until the matching command succeeds. Separate
  validation/rejection, version conflict and an unknown network outcome.
- Retry an unknown outcome using the same operation ID, timestamp and payload.
  Do not silently substitute a new operation or overwrite a newer server version.
- Discard stale callbacks when the account, organization, session or route changes.
- Read access and delegation are distinct. Reuse source-backed delegation ceilings
  and scopes; do not infer power to grant a responsibility from merely holding it.
- Preserve source guards, independent approvals, current membership, audit and
  idempotency. No bypass is justified by making a screen easier to reach.

## Accounting desk

The shared native Accounts & ledger workspace exposes current supplier/customer
obligations and exact posted period movements and journal lines. The underlying
read projections use the existing AP/AR/reversal ledger and recognized allocation
balance view, with existing financial reporting authority (both legacy reporting
checks for ledger reads), no new feature switches or write actions. Period
movement is not a company-wide trial balance, opening/closing balance, cash
balance or financial statement. Keep currencies separate and disclose missing
due dates and allocation exceptions. The mixed bank/cash PostgreSQL fixture
exercises the new read store against the same independently posted/allocated
source; the native client tests scope and source contracts.

## Settlement journal coverage

The existing daily finance desk now includes source-bound settlement posting.
Company settings create an approved bank/cashbox ledger account tied to its
actual instrument and currency. AP/AR allocations, physical vouchers and
corrections of already-posted bank matches can produce exactly two immutable
GL lines in the current open period, with unique source keys and an exact
receipt. Existing source and GL operator responsibilities are checked, and
posting never initiates a payment or claims a bank refund. Settlement journals
participate in the existing period-close snapshot. The wider ledger query is
explicitly requested by the current client; older readers retain the earlier
partial view. Opening balances, payroll, employee advances, expenses, other
unallocated bank movements and a full financial-statement ledger remain
outside this coverage. Do not enable the full-ledger-ready closure switch on
the strength of this increment alone.

## Employee custody and paid-cost journals

The same daily posting desk can now record approved bank-backed advance and
imprest funding as assets, and return settlements against the original posted
custody account. Accepted imprest receipts and bank-confirmed expense
reimbursement support an explicit company accounting treatment for first
recognition into a single approved expense or asset account. The operator
must verify that the amount was not already recognized; split treatments and
settlement of a previously accrued expense liability are not supported by this
flow. Source amount, approval, organization, account classification and
currency are independently guarded in PostgreSQL. No automatic cost
classification, payroll posting, tax calculation or additional money transfer
is inferred. The previous native report and queue contracts remain available
without the new opt-in query parameter. The full-ledger close readiness gate
remains unchanged. The mixed bank/cash test now exercises the independently
approved custody, return, accepted receipts and reimbursement sources with
transaction-local synthetic data.

## Native home and personal context

The shared native home exposes specialized workspaces only after a successful
current source authorization read. One account can reach all of its available
operational groups; job titles and team labels never grant access. Personal
employee details and own payslips belong to Account rather than People or
payroll administration. The employee directory needs its own authorized read.
Direct home/account routes use the existing current-source preflight and
return to their originating context. Loading, failed reads, cached assignments
and a confirmed empty assignment list are distinct presentation states.

The shared tests verify route selection, revoked and duplicate routes, personal
profile scope and assignment source states. Device rendering and authenticated
role acceptance remain separate from these source checks.

## Internal support notes

The native support desk opens the selected source ticket and loads its private
notes from the authenticated project-scoped API. A note mutation is available
only after a current successful note read. A lost response retains the same
body and operation identifier; a confirmed write is distinct from a later
failed read. Both hosts bind the API client to their current session. In-memory
note drafts and unresolved commands may continue after verified reauthentication
of the same actor and organization, but not after an explicit sign-out, fresh
sign-in, or a change of actor/organization. The current server authorization
check still applies to every read and replay. No note is sent externally.

## Onboarding and operational choices

The native employee onboarding workspace reads applicable active policy choices
and the current case from an authorized source. It offers only the requirement
resolutions returned by that source. Document verification and employee legal
acknowledgment are separate operations; an administrator must not satisfy them
with a generic manual checkbox.

Preserve a prepared or uncertain onboarding operation when its tab is removed
from composition. Scope that saved state to the employee and full authenticated
session. A matching command receipt confirms the operation, while a subsequent
current-state read establishes the displayed employee state. A failed read after
a successful command must not announce activation or cause a second operation.
Current authority is checked before idempotent replay; mutable policy or employee
state must not prevent an authorized replay of an already recorded start.

Operational supplier search uses the existing buyer authority and organization
scope. Only a currently active supplier and its latest active organization link
qualify. Search before applying the bounded result limit, invalidate choices
when context changes, and recheck eligibility when executing the command.
Selecting a supplier does not grant authority to create or maintain supplier
identities or links.

Identity errors use typed, localized presentation and an appropriate recovery
action. Raw provider or server messages are not product copy. Retain a separate
sanitized support reference and preserve callback, session and authorization
checks. Re-run native shell assertions when changing these visible messages.

Android window chrome and system insets are part of the host contract. Keep the
Compose shell out of system bars, display cutouts and the visible keyboard;
do not layer a platform ActionBar over its own navigation or product heading.
Native smoke checks must inspect the product heading, subtitle and controls
inside the safe content bounds, exercise language changes using observed UI
bounds, and retain the UI hierarchy alongside screenshots. Text presence alone
does not establish that a control is visible or reachable.

## Availability and account recovery

Implemented internal operations use the server's operation availability catalog.
Both native hosts consume the same catalog instead of requiring a second set of
desktop environment or Android build switches. An explicit server pause wins;
an unknown key in a supplied catalog is unavailable. Availability is separate
from source permission, independent approval, provider configuration and actual
source completeness. Do not turn a readiness assertion into a feature switch.

The managed account recovery flow is described in
`EMPLOYEE_ACCOUNT_RECOVERY_CONTRACT.md`. Provider administration dispatches each
mutation once, with bounded timeouts and no automatic retry or redirect. A lost
or unsuccessful password-reset response remains unconfirmed. Replaying a stored
operation returns its recorded metadata and never dispatches another reset or
recovers a previously displayed secret.

Exercise Spring construction of managed workflow services as well as direct
domain fixtures. Kotlin default constructor parameters can create overloads
which direct tests accept but the application container cannot select. The
packaged server startup against migrated PostgreSQL is a separate required gate.

## Completion discipline

Update the existing private closeout record with new defects, results and
remaining limits. The public change description should identify the repaired
behaviour and tested paths. Do not claim that all forms, roles, devices or
integrations are closed because one interaction passed. Keep the current draft
review open until its required verification and source reconciliation are met.
