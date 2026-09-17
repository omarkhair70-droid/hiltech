# HILTECH Conflict Policy

Status: SYSTEM MODEL v0.1 / NOT FROZEN

## Principle
Not all conflicts are equal.

Never adopt universal last-write-wins for operational/financial HILTECH data.

---

# Conflict Class 1 — Independent Append

Example:
Two technicians add separate photos/notes.

Resolution:
usually both accepted.

---

# Conflict Class 2 — Mergeable Set

Example:
Different non-exclusive metadata fields updated.

Potential:
field-level merge if domain rules allow.

Requires explicit domain definition.

---

# Conflict Class 3 — Stale Assignment

Example:
Technician completes Task A offline after PM reassigned/cancelled it.

Resolution:
do not silently mark current task complete.
Preserve local evidence/work as submitted record and require domain review/reconciliation.

---

# Conflict Class 4 — Resource Custody

Example:
Two offline devices attempt checkout of same Fluke.

Resolution:
first authoritative server acceptance wins custody.
Second command rejected/conflict.
User gets current custodian/context and recovery action.

Never merge.

---

# Conflict Class 5 — Stock Quantity

Example:
Two offline issues consume same limited stock.

Server validates against authoritative availability.
Could accept, partially accept, or reject according to explicit warehouse policy.

No negative inventory by accidental last-write.

---

# Conflict Class 6 — Document Revision

Example:
Technician works from revision 4 while revision 5 became approved.

Do not erase evidence.
Mark work/evidence with used revision.
Policy decides review/rework requirement.

---

# Conflict Class 7 — Approval Version

Example:
Mohamed opens approval; Ahmed edits underlying payroll/PO before Mohamed acts.

Old approval cannot apply.
Server returns SUPERSEDED / VERSION_CHANGED.

Mohamed reviews new version.

---

# Conflict Class 8 — Financial

Example:
duplicate payment/expense/payment batch command.

Must use idempotency and authoritative reconciliation.
No client-side merge.

---

# Conflict Class 9 — Permission Revocation

Example:
employee works offline then is removed from project/offboarded.

Server rejects commands no longer authorized according to security policy.
Preserve local data for controlled recovery/support if needed; do not silently publish.

---

# Conflict Class 10 — Deleted/Archived Object

Offline change targets object now closed/archived.

Return explicit object lifecycle conflict and allowed recovery path.

---

# UX Rules

Conflict message should answer:
- What could not be applied?
- Why?
- What is current truth?
- Is local work safe?
- What can user do now?

Avoid generic "Sync error".

## Completion gate
Each offline-capable feature must map to a conflict class or define a new one.
