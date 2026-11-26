# Employee responsibility administration

This contract adds employee-centric administration of existing operational
responsibilities. It does not change a business command's authorization or
approval rules. The domain binding remains the source of operational authority.

## Separate authority to delegate

`responsibility_administration_binding` is a company-owned, direct identity
grant. Each row names exactly one organization-scoped catalog key the
administrator may grant or end. The separate `can_delegate_administration` bit
controls whether that administrator may delegate this management authority to
another employee. Current membership in an active HILTECH
organization is required. People administration permits viewing the private
employee record; it does not imply any delegation ceiling. `OWNER_FINAL`, a job
title, holding the requested responsibility, and membership alone confer no
delegation authority.

The initial administrator is provisioned once through the trusted deployment
entry point with an explicit operator, current member, approved source reference,
an enumerated ceiling, and an explicitly approved onward-delegation boolean. It neither creates an identity nor grants People access
implicitly. No default identity or broad wildcard is seeded. Exact setup replay
is harmless; the trusted initial setup cannot alter existing governance history.
Subsequent delegation happens in the application's separate administration
delegation panel. A current onward-delegating administrator may grant a subset
of their own onward-delegable keys, optionally permitting further delegation for
those same keys. This does not automatically grant the recipient the operational
role. Ordinary employee assignments use the separate responsibility panel.

An approved current delegate whose ceiling explicitly contains `PEOPLE_ADMIN`
can activate that ordinary role for their own authenticated identity from
Company administration, even before the first employee exists. This fixed
command accepts only an operation ID; it accepts no recipient, role selector,
or governance fields. It checks the ceiling and membership before replay and
under the organization lock, requires recent reauthentication, preserves source
history, audits the change, and uses the normal People projection. The UI
distinguishes an assigned role from access confirmed by People authorization.
This removes the first-employee bootstrap dependency without inferring any
authority from membership or extending the original governance ceiling.

Governance self-grants and self-upgrades are rejected. Versioned ending is
allowed only while at least one current administrator who may delegate that key
remains; the user must transfer administration first. The organization lock
serializes competing grants/ends and this continuity check. Actor authority is
checked before idempotent replay and again in the transaction. Delegation uses
the current employee/account/version, recent reauthentication, immutable receipt,
and audit; no source history is deleted. A normal People administrator sees no
delegation panel unless they separately hold onward-delegable administration.

## Typed scope and source ownership

The organization catalog contains People administration, project portfolio
administration, Sales view/write/cost/release/award, Payroll read/prepare/review,
supplier master administration, Finance payroll disbursement preparation/review,
and bounded approval-policy administration. The implementation writes the respective
People, Projects, Commercial, Payroll, and Supplier source binding, then uses
existing People/Projects projection bridges where required. Payroll review
authority does not approve or pay a payroll run. Supplier registration does not
grant purchasing or payment authority. `APPROVAL_POLICY_ADMIN` can configure only
policy operations explicitly exposed by their owning workflows; it cannot
approve a subject, change final owner authority, or disable an independent
approval requirement. No approval-engine authority bindings are changed by the
responsibility catalog.

Warehouse and storage-place delegation retain their existing scoped commands.
Project-specific assignments retain their project command owner. A summary
identifies its organization catalog scope and never claims that it represents
every resource assignment. Direct and team-inherited bindings are distinguished;
inherited access is not ended from a direct employee assignment.

## Commands and receipt semantics

Grant and end are online, immediate, typed commands with an operation ID and
matching idempotency header. The selected employee version and linked identity
are captured in the request. Grant requires a PREBOARDING or ACTIVE employee and
a current recipient account. An administrator may assign an operational
responsibility to their own employee account only within the same explicitly
approved per-key delegation ceiling. Requiring a second administrator for that
operation would create an unnecessary dependency without reducing that ceiling.
This does not confer governance authority or permit a transaction's maker to
approve their own transaction; each domain retains its independent approval
checks. End requires the exact
direct source binding, authority key, recipient, and version. A changed employee,
binding, or ceiling requires refresh before a new command. Authority is checked
again inside the transaction; a revoked administrator cannot replay a receipt.

The implementation serializes employee changes, uses the existing transactional
idempotent command executor, records an audit event, and enqueues the relevant
authorization projection in that transaction. A retry uses the original frozen
operation and payload; an uncertain network result is never replaced by a new
operation. A receipt confirms that command, while a subsequent read reports
current access, which may already have changed or been ended.

People/Projects direct role re-grants allocate a version greater than the prior
binding history for that organization, identity, and role. The projection guard
compares generations across binding IDs for the same relation; restarting at one
after an ended binding would incorrectly retain the older absent projection.

The native workspace uses readable responsibility names, contextual descriptions,
searchable choices, a confirmation dialog, explicit direct/inherited status,
localized failures, and a refresh action. It never asks for identity IDs or raw
authority configuration. Both native hosts mount the shared composition using
their existing API client and authenticated session scope.

## Existing staff account setup

Account preparation accepts PREBOARDING and ACTIVE employees. It never changes
the employee state/version, starts an onboarding case, or reopens a completed
onboarding process. People authority is checked before entering the serialized
employee operation and again after acquiring it. Current employee version is
required before new provider work; an authorized successful email receipt can
be read after unrelated employee changes without sending again.

Availability reads the current identity and membership instead of treating a
historical READY invitation as proof of current access. A locked/revoked account,
suspended/ended/expired membership, or different existing identity cannot be
replaced through account creation. Provider recovery checks the exact invitation
and source state before delivery. Local activation affects only that invitation's
membership and rechecks employment eligibility. A provider conflict retains the
attempt for review and is reported separately from an outage.

Native retries preserve the operation, employee version, login, and delivery
choice when the result is unknown; receipt validation binds the response to the
same operation and employee. One-time credentials remain transient and are never
stored in invitation history or saved UI state. Replaying an already completed
one-time credential handoff does not reveal or reset that credential.

## Acceptance boundary

Service tests must cover separate delegation authority, exact organization/key
ceiling, membership loss, self-assignment within and outside that ceiling, employee/account changes, stale versions,
inherited binding protection, idempotent retries, and authorization before replay.
Database tests must exercise actual migrations and domain projections. Shared UI
tests do not substitute for Windows/Android role journeys against the intended
server. Native acceptance requires creating an employee, preparing the account,
assigning and ending approved responsibilities, logging in as that employee,
and observing both allowed operations and denied independent approvals.

## Bounded company review setup

Company setup exposes three bounded workflows: `PAYROLL_SOURCE_REVIEW` with authority
`PAYROLL_SOURCE_REVIEWER`, and `FINANCE_PAYROLL_RESULT_REVIEW` with authority
`FINANCE_PAYROLL_RESULT_REVIEWER`, and Payroll run approval with authority
`PAYROLL_RUN_REVIEWER`. The run workflow uses the actual server-owned
`hiltech.payroll.run-approval-policy-key`, defaulting to `PAYROLL_RUN_REVIEW`;
the UI never accepts arbitrary policy keys. Existing policies attached to
separately governed authority (including `OWNER_FINAL`) remain visible and
protected from replacement by this bounded setup. All managed routes have one required review step and
a required reason.
Only a current `APPROVAL_POLICY_ADMIN` may inspect or configure this setup.
The selected reviewer is a named current employee with the explicit current
domain REVIEW grant and usable membership. The endpoint accepts no arbitrary
policy key, mode, authority key, approval-disabled switch, or raw principal.

Configuration captures policy/binding IDs and versions plus the selected
employee/account/version. An organization lock, current authority checks before
replay and in the transaction, a typed idempotent receipt, and audit protect the
change. Prior policy and reviewer rows are retired, and a new immutable policy
revision and direct reviewer binding are created. Existing team reviewer
configuration remains visible and is replaced only by an explicit user command.

Completed reviews remain evaluated against their pinned policy revision at the
decision time. Pending requests assigned under a replaced policy require a new
immutable input revision and review request; they are not silently reassigned.
The setup view reports pending requests and explains this recovery before the
user confirms replacement. Policy setup neither grants Payroll REVIEW nor allows
the person who prepared an input to approve it.
