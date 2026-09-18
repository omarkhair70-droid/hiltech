# SPIKE-09 Result — OpenFGA HILTECH Authorization

Date: 2026-09-18
Decision: **ACCEPT — OBJECT/ACTION AUTHORIZATION MODEL PASSED**

## Environment

OpenFGA v1.20.0.

GitHub Actions run: 35296729939.

## Model

Eight representative types:
- user
- organization
- project
- work_order
- asset
- invoice
- payroll_run
- purchase_order

Organization relationships included:
- member
- employee
- owner
- finance
- warehouse
- procurement
- approval_delegate

The fixture used 31 base tuples.

## Checks

27 representative allow/deny checks passed.

Proven examples:

### Project / Work
- Mohamed: project viewer = allowed.
- PM: project viewer = allowed.
- assigned technician: project/work viewer = allowed.
- authorized client organization member: project/work viewer = allowed.
- supplier user: project/work viewer = denied.

### Asset
- assigned project technician: can_checkout = allowed.
- warehouse user: can_checkout = allowed.
- client user: can_checkout = denied.

### Payroll
- Mohamed: viewer = allowed.
- Ahmed/finance: viewer = allowed.
- technician: viewer = denied.
- client: viewer = denied.

### Invoice
- finance: viewer = allowed.
- authorized client member: viewer = allowed.
- technician: viewer = denied.
- supplier: viewer = denied.

### Supplier PO
- procurement: viewer = allowed.
- relevant supplier member: viewer = allowed.
- client: viewer = denied.

### Variation Approval / Delegation
- Mohamed: approver = allowed.
- PM: approver = allowed.
- Ahmed: denied before delegation.
- Ahmed: allowed after approval_delegate tuple.
- Ahmed: denied again after tuple deletion.

## Accepted Direction

OpenFGA is a credible HILTECH engine for relationship-based **object/action authorization**.

Good fits:
- organization membership,
- roles within organization,
- project membership/assignment,
- external organization isolation,
- resource relationships,
- delegation.

## Boundary

Do not force all security into OpenFGA.

Still server/application owned:
- field-level redaction,
- sensitive payroll field shaping,
- re-auth/MFA obligations,
- workflow-state prerequisites,
- amount thresholds,
- offline permission revalidation,
- audit reasons.

## Production Status

Disposable authorization evidence only.
Production tuple lifecycle, cache/consistency policy, deployment and integration remain freeze work.
