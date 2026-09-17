# Wireflow — Mohamed / Owner Approval

Status: EXPERIENCE WIREFLOW v0.1 / NOT VISUAL DESIGN

## Goal
Mohamed receives only high-value/exception decisions and can understand + act safely from mobile without becoming the operational bottleneck.

---

# Primary Flow

```text
Push / HILTECH Inbox
        ↓
Needs You
        ↓
Approval Card
        ↓
Approval Detail
        ↓
Exact Subject Version
        ↓
Context / Impact / Evidence
        ↓
Approval Flow / Who Already Approved
        ↓
Approve / Reject / Request Change
        ↓
Re-auth if required
        ↓
Server validates:
  permission
  subject version
  policy
  current state
        ↓
SUCCESS
        ↓
Ahmed/Requester updates instantly
        ↓
Inbox item resolves
```

---

# Home Entry

Mobile Home:
- Needs You count.
- top 3 high-priority approvals.
- each item shows:
  - subject.
  - why Mohamed is needed.
  - amount/impact if allowed.
  - requester.
  - waiting time.
  - deadline.
  - risk.

No generic "You have 7 notifications."

---

# Approval Card

Example:

```text
Payroll — September 2026

Prepared by: Ahmed
Employees: 42
Net total: [restricted amount]
Version: v4
Reason you are required:
Owner approval policy

[Review]
```

Possible alternate subjects:
- Purchase Order.
- Payment.
- Variation.
- Asset write-off.
- Discount/quote.
- emergency exception.

---

# Approval Detail

Sections:

1. Subject identity.
2. Exact version.
3. Decision summary.
4. Financial/operational impact.
5. Source context.
6. Supporting evidence/documents.
7. Approval chain.
8. History/comments.
9. What happens after approval.
10. Actions.

---

# Action — Approve

```text
Tap Approve
   ↓
Policy check
   ↓
Re-auth required?
   ├─ No → Submit
   └─ Yes → biometric/passkey/IdP re-auth
                   ↓
             Submit exact version
                   ↓
          Authoritative server result
```

---

# Version Changed Conflict

Scenario:
Ahmed edits payroll while Mohamed is reviewing.

```text
Mohamed opens v4
        ↓
Ahmed creates v5
        ↓
Mohamed taps Approve
        ↓
VERSION_CONFLICT / APPROVAL_SUPERSEDED
        ↓
UI:
"This payroll changed while you were reviewing it."
        ↓
[Review v5]
[Close]
```

Never apply v4 approval to v5.

---

# Permission Changed

```text
Approval open
   ↓
Mohamed authority/delegation changed
   ↓
Tap Approve
   ↓
PERMISSION_DENIED / APPROVAL_REASSIGNED
   ↓
Current truth shown
```

---

# Offline

Mohamed may open cached approval context offline.

UI:
- "Last updated 8 minutes ago."
- decision controls disabled.

```text
Offline
  ↓
Review cached data
  ↓
Approve disabled
  ↓
"Connect to approve securely"
```

No offline final approval.

---

# Reject

Reject may require:
- reason.
- optional comment.
- request owner.

Result:
subject returns to requester according workflow.

---

# Request Change

Useful when:
- not rejecting business intent,
- specific correction required.

Examples:
"Change beneficiary bank details."
"Explain 18% payroll variance."
"Attach revised supplier quote."

---

# Cross-Device

Desktop Mohamed:
Can inspect deeper context.

Mobile Mohamed:
Can decide quickly.

Same ApprovalRequest ID and state.

If handled on Desktop:
mobile push/inbox resolves.

---

# Empty State

If no approvals:
```text
You're clear.
No decisions need you right now.

Company Pulse continues below.
```

Do not manufacture dashboard noise.

---

# Error States

- Reauth failed.
- approval already handled.
- subject deleted/cancelled.
- provider/integration pending.
- network lost before submit.
- server timeout after submit.

Important:
if request outcome uncertain, client queries exact ApprovalRequest before retrying.

---

# Motion Semantics

- card expands into detail preserving object continuity.
- successful approval transitions state, then resolves card.
- superseded approval visually replaced by newer version.
- no celebratory/confetti motion.

---

# Success Criteria

Mohamed can:
- understand why he is needed in <30 seconds.
- inspect enough evidence.
- make safe decision.
- never approve stale subject.
- never chase Ahmed for "what happened after I approved?".
