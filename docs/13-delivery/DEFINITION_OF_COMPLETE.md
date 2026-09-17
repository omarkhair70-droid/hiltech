# HILTECH — Definition of COMPLETE

Status: ACTIVE GOVERNANCE RULE

## Principle
Nothing in HILTECH may be marked COMPLETE merely because documentation, UI, backend, or code exists.

COMPLETE means the capability is fully specified, implemented, integrated, tested, observable, secure, and deployable within the agreed release boundary.

## Completion levels

### 0. DISCOVERED
We know the capability exists as a real need.

### 1. RESEARCHED
Relevant business reality, market patterns, technical constraints, and reference systems have been investigated and sources/notes are recorded.

### 2. PRODUCT-DEFINED
The capability has:
- personas/users
- jobs-to-be-done
- entry/exit conditions
- happy paths
- error paths
- edge cases
- permissions
- data visibility
- notifications
- audit needs
- offline behavior
- mobile/desktop behavior
- integration requirements

### 3. DOMAIN-DEFINED
The capability has:
- objects/entities
- ownership rules
- states/state machine
- commands/actions
- events
- invariants
- validation rules
- retention/history rules
- conflict rules

### 4. EXPERIENCE-DEFINED
The capability has:
- information architecture placement
- screen/surface map
- interaction model
- responsive/adaptive behavior
- empty/loading/error/permission-denied/offline states
- accessibility behavior
- motion behavior
- typography/icon/data visualization requirements

### 5. TECHNICALLY-DEFINED
The capability has:
- client architecture
- backend/module ownership
- local persistence behavior
- API/command/query contract
- database schema strategy
- workflow/orchestration strategy where required
- integration adapters
- security controls
- audit/observability
- failure/retry/idempotency strategy
- test strategy
- deployment dependencies

### 6. BUILD-READY
The repository explicitly defines:
- exact module/package/app placement
- file/folder structure where appropriate
- library/framework decisions
- dependency boundaries
- implementation order
- migrations
- test fixtures
- rollout/feature-flag plan if required

At this point implementation should not require rediscovering the product.

### 7. IMPLEMENTED
Production-quality code exists for the agreed scope.

### 8. INTEGRATED
The capability works through all required upstream/downstream domains.

Example:
Payroll is not integrated until HR inputs, approvals, finance execution, employee visibility, audit, and payment handoff all work as designed.

### 9. VERIFIED
- unit tests
- domain tests
- integration tests
- UI tests where valuable
- permission tests
- offline/sync tests where relevant
- failure/recovery tests
- audit verification
- realistic test data
- acceptance criteria

### 10. OPERABLE
- logs/metrics/traces exist
- support/debug route exists
- backup/recovery implications are known
- security monitoring is defined
- deployment/rollback is defined
- runbook exists where operationally significant

### 11. RELEASED
The capability is safely deployed to its intended users/environment.

### 12. COMPLETE
Only now may a capability be labelled COMPLETE.

## Rule for documentation
A document may be complete as a document while the product capability remains incomplete.

Never write:
> Payroll — COMPLETE

when only:
> Payroll product specification — COMPLETE

is true.

## Rule for partial releases
We may release a deliberately bounded vertical slice, but it must be complete within its declared boundary.

Example:
"Warehouse Asset Checkout v1" can be COMPLETE if its declared scope excludes procurement and calibration, only if those exclusions are explicit and its integration contract with future domains is already defined.

## Freeze rule
Before implementation begins in earnest, each major capability should be at least BUILD-READY, not merely PRODUCT-DEFINED.

## Anti-patterns
- "We'll remember the missing edge cases later."
- "Backend first; we'll decide permissions later."
- "UI now, data model later."
- "Add offline after launch."
- "We'll pick libraries while coding."
- "Let's call it complete because the demo works."

All are prohibited unless recorded as an explicit time-boxed prototype outside the production path.
