# Cashbox opening: current custody, internal code and confirmed result

This is the implementation contract for the existing zero-balance cashbox
opening journey. Native device and deployed-server acceptance are separate
verification gates.

## Source and authority

The existing cash preparation authority admits opening requests and a minimal
custodian directory. It uses the closed `FINANCE_CASH_PREPARE` operational source
or the current configured legacy source, as described in the operational
responsibility contract. People administration is not required to select a
custodian. Directory results contain only a current employee's name, employee
code, account reference and source version. The employee, identity and company
membership must be active in the exact company. The current reviewer is
excluded from selectable custodians. An authorized preparer may select their
own current employee account.

The opening reviewer must differ from both the preparer and the custodian; the
preparer and custodian may be the same person. A zero opening creates no money,
so it does not need a third participant. V0126 replaces only this inherited
opening restriction; cash movements retain their separate source and review
requirements. The opening policy is required and SINGLE; neither
an operational grant nor opening-screen access grants approval power. Approval
continues through the existing engine and database source guards.

Preparers see their own opening history. Existing cash-report readers may see
company history and retain their existing balance permission. Reading an
opening does not grant cash recording, reconciliation or broader employee data
access. The new directory and workbench responses are not cacheable.

## Numbering and immutable history

The native form chooses a named custodian, currency and company custody
reference. It does not ask for account IDs, proposal IDs or a new cashbox code.
The server allocates `CBX-000001`-style company internal codes inside the actual
idempotent preparation transaction. V0126 supplies a transactional counter,
reserves existing exact manual codes and verifies automatic code/sequence
lineage. A transaction rollback releases its allocation; a replay returns the
recorded code. These codes do not claim statutory invoice or payment numbering.

Existing manual codes, immutable proposals and active cashboxes remain intact.
The legacy preparation payload and fingerprint format are retained; replay
uses its recorded subject version rather than rebuilding its fingerprint from
a later reviewer configuration. New automatic intents pin the current
custodian and review source versions before allocating a number. Current
operational authority is checked before replay and again inside the company
lock before a write. The same authority is checked before submitting review
and opening an approved cashbox.

Stale proposals remain visible with a source-change state. Recorded rejection,
change request, cancellation and supersession states retain their meanings.
History is not deleted to clear the work queue. A changed automatic proposal's
former or restricted custodian cannot activate the old opening source. Opening
still creates zero cash; subsequent cash movements keep their own review and
evidence requirements.

## Native command continuity

The shared workspace uses a wide workbench on desktop and a stacked form on
narrow screens, with Arabic/English labels, named choices, localized dates and
human errors. The company review setup hook is optional and navigates through
the existing company administration route.

Preparation, submission and activation each have a frozen, serializable intent
and an explicit confirmation. A lost response preserves that exact request
across navigation; retry does not allocate another operation. Receipts remain
visible after a proposal leaves the pending list. An activation retry can
confirm the original operation even when a fresh source read is already ACTIVE;
the server still checks the exact original activation operation and actor.
Source comparison pins financial identity/version fields, not a mutable display
name or action-button label.

## Verification boundary

Unit tests cover directory authority, independent people, version changes,
legacy fingerprints, counter placement, current-authority replay, retained
stale history and activation recovery. Shared client tests cover scoped search,
automatic payloads, matching receipts and the exact uncertain-response retry.
Rollback-only PostgreSQL tests use real source queries, the command executor,
transactional numbering, audit and V0126 constraints with synthetic identities.
They do not constitute provider, native device or deployed-company acceptance.
