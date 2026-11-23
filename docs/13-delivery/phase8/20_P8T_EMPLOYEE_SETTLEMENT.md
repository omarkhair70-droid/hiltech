# Phase 8T — Employee settlement domain separation

Status: isolated draft, NOT a real payment/receipt/settlement booking.

## Code
`FinanceEmployeeSettlementRules.validate` distinguishes:
- Expense reimbursement: employee previously spent funds, requires verified
  bank debit paid to employee; no payroll loan recovery.
- Employee advance: company-funded employee borrowing; requires signed
  repayment evidence through bank credit or an independently authorized
  payroll deduction source. No inferred Payroll Phase 9 deduction.
- Financial imprest: custody of company money, not personal borrowing.
  Accepted receipts may reduce custody without new cash movement;
  cash return requires verified bank credit.

All validate company authority, actually funded source with bank evidence,
final settlement evidence, amounts and aggregate remaining balance, plus
deterministic review version. Negative JUnit ensures an unfunded self-service
draft cannot become a settled asset.

## Critical missing operational work
Company policy and exact approval subjects, immutable funded-source ledger,
transactional settlement DB/event table and concurrency guards,
verified evidence ownership, Payroll9 source integration,
partial expense reimbursement ledger posting, bank source import and confirmed
cash matching, native UX, staging and DevOps migration orchestration.
No money transferred, balance written or real settlements occurred.
