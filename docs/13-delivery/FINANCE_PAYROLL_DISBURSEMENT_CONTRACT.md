# Finance payroll disbursement records

The internal workflow reconciles actual bank movements with released payroll.
It does not execute a transfer or post an accounting journal. `FINANCE_PAYROLL_PREPARE`
and `FINANCE_PAYROLL_REVIEW` are separately delegated current responsibilities;
they are not inherited from People administration or ordinary payroll access.
The application configures the fixed `FINANCE_PAYROLL_RESULT_REVIEW` SINGLE,
required approval policy and an independently authorized named reviewer through
the company review-workflow administration API. No grants or company data are
seeded by the migration.

An ordinary internal instruction batch freezes every positive employee net
from one released payroll review envelope. Zero-net employees need no instruction.
A correction with `SETTLED_DIFFERENTIAL_AUTHORIZED` uses the separately reviewed
per-employee remaining amount against cumulative, verified posted prior settlement,
never another full salary instruction when prior funds remain with the employee.
An evidenced partial net (including actual posted bank returns) can participate
in the same clearance: corrected net minus verified net paid determines the
remaining payment or recovery. The previously unpaid liability is already in
the ledger; the correction preview subtracts that balance from the new payable.
A missing result, unposted event, unresolved proposal or voided match is not
proof of zero payment and blocks the differential. All-return and separately
reviewed no-transfer cases retain their existing paths. This does not add
split bank matching against a single instruction. A negative difference requires an explicit approved
OTHER_ASSET account selected for employee overpayment receivables. Finance
clearance freezes the source fingerprint and individual recovery schedule.
Posting the replacement accrual reverses the original base lines, records the
new accrual, and reclassifies the overpayment from payroll payable to the
selected receivable account in the same journal. Additional payables and
receivables are not netted across employees. A recovery-only batch has no
payment instructions. Recognition is not collection: no automatic wage
deduction, bank collection or new employee funding is introduced. Bank-backed collection uses the existing employee settlement preparation,
independent approval, recording and general ledger posting. The posted
receivable is a distinct PAYROLL_OVERPAYMENT source, not company funding.
The existing shared bank allocation cap and source balance cap apply to each
partial collection. Operational remaining and GL remaining are separate; a
recovery employee is unresolved until all collections have been posted.
Automatic payroll deduction remains unsupported. Recovery corrections currently
require the same employee cohort; another correction of a run carrying a
recovery schedule remains blocked pending explicit recovery reconciliation.

A preparer selects an employee and an independently approved bank statement
line by displayed reference, date, account and amount. Amount/currency come from
the frozen payroll, not user entry. SUCCESS requires a matching DEBIT; RETURNED
requires a matching CREDIT after evidenced success. The statement's canonical
READY evidence and immutable source hashes are rechecked. The preparer records
the business reason and confirms the beneficiary match for independent review.
An upload alone is never a payment result. A reviewer approves or rejects the
exact source-versioned proposal; only approval records a payroll result. The
reviewer cannot be the preparer or the beneficiary of the payment. Existing
bank allocation limits include payroll, preventing double use across AP/AR,
employee funding, settlements, reimbursements and payroll.

An erroneous SUCCESS match can be explicitly voided by an independently
approved correction. This retains all history, releases that bank allocation,
and returns the payroll result to UNKNOWN. It does not reverse money or erase a
bank transaction. An actual returned transfer uses a separate CREDIT source.
Payroll matches participate in the existing bank reconciliation totals and
source fingerprint. A signed statement cannot acquire a later payroll match or
match correction; this preserves the signed source instead of silently changing
its meaning. Successful matches also recheck the latest released payroll under
the period lock, while historical published payslips remain available.

The decision workspace opens the original canonical bank CSV through a protected,
proposal-scoped endpoint. Only the exact preparer/uploader with current Finance
preparation responsibility or the current independent reviewer can receive it.
The source joins, READY upload, byte size, SHA-256, canonical CSV and linked bank
line are checked, and authority is rechecked after the bounded private storage
read. No generic bank-file permission or signed download URL is created. An
immutable receipt and audit record identify the exact proposal/version/file/user
that received the bytes; recording an approved result requires that reviewer's
receipt. This demonstrates opening the source, not proof of human reading. The
native viewer keeps the file in the current session and supports reading both
the bank rows and original text without an external export.

When a pending proposal's source changes, its original reviewer can close the
outdated request while still holding the current named review assignment and
Finance responsibility. This calls ApprovalEngine's own `SUPERSEDED` transition;
it never records a payment, rejection or accounting result. The exact operation
remains replayable. Replacing a reviewer is governed separately by the bounded
company policy administration workflow.

The payment-results adapter exposes only these immutable, approved events.
Unrecorded instructions remain PAYMENT_NOT_RECORDED, missing result service is
PAYMENT_STATUS_UNAVAILABLE, and zero net is NOT_APPLICABLE. Published self-only
payslips remain visible independently of payment setup. Nonempty instruction
batches must still match the entire frozen payroll cohort before results are
trusted. Published payroll and actual payment remain separate facts.

Native commands retain their exact operation IDs after uncertain transport
outcomes. Receipts and follow-up refresh are separate states. Public tests use
synthetic identities and evidence only; production acceptance is recorded in
the private canonical closeout register.

## Historical payroll opening

A cutover payroll payable is an immutable CREDIT line in the existing balanced
opening journal. The schedule identifies each company employee, legacy payroll
reference, historical period end and positive unpaid net residual. Its exact
sum must equal the control account. The source is not an advance, expense,
new payroll run, bank result or authorization to pay. Duplicate employee
periods/references and overlaps with already released payroll are rejected;
later payroll release also rejects overlap with a recorded opening. The
existing opening journal authority and company/source references apply.
Historical settlement through bank evidence and its operational remaining
balance are a separate follow-up; no payment source is fabricated by opening.
