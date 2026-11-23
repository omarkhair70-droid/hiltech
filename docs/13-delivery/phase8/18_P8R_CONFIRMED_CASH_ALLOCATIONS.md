# Phase 8R — Source-confirmed cash allocation preflight

Status: isolated Draft, DEFAULT OFF, NO money movement or bank import.

## Implemented
- Strongly typed `FinanceCashAllocationPlan` for approved source + confirmed
  bank line, cash direction, currency, partial allocation, cumulative source
  and bank allocations, independent approval, bank source SHA256 and date.
- `FinanceCashAllocationRules.validate` rejects unposted AP/AR or unconfirmed
  advance/imprest, missing bank evidence, cross-currency, wrong debit/credit,
  negative/over-precision, over-allocation and unapproved cash linking.
- Deterministic exact hash/version, source and bank remaining balances.
- JUnit regression and dedicated CI. No SQL writes or REST actions yet.

## Missing before Payment/Collection or employee settlement is operational
- Actual authoritative bank account registry and signed statement parser/import.
- Independent company policy to review bank-source line evidence; canonical
  unique account/external transaction ID and immutable event.
- DB-level row locks across SOURCE and BANK allocation sums plus unique
  operation ID, exact separate approval and append-only allocation records.
- Confirmed actual funds disbursed to employee for Advance/Imprest: a DRAFT
  request or Payroll approval is not a funded asset.
- Expense reimbursement must follow separate employee claim review, approved
  source and *confirmed* bank debit, not approval as cash paid.
- Receipt/collection register and AP/AR aging reports must distinguish bank
  source available, allocated, bank-reconciled, and unallocated.
- Native screens, real permission tests, approval binding, globally numbered
  migrations, staging, and combined CI before activation.

No user money will move. These rules are only the preflight prerequisite.
