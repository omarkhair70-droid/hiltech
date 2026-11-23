# HILTECH Phase 8W — Physical cashbox / signed receipt & supplier payment

Status: **DRAFT / feature OFF / source-backed code only**. This extends Phase 8U
bank settlement; it does not replace bank handling, claim a live bank API,
authorize arbitrary cash creation, or enable the production cashier.

## Real user workflows added

1. Company Finance prepares a company-owned zero-balance cashbox, binds a
   named active company custodian and a company custody reference. Another
   authority approves the exact immutable opening; only after approval can
   the register become ACTIVE. Original company approval and activation
   operation IDs are retained. No synthetic historic money is inserted.
2. Finance prepares a physical-cash voucher against a **posted** customer
   receivable (AR receipt) or **posted** supplier payable (AP payment).
   The voucher fixes cashbox, source GL journal, currency, amount, company
   voucher reference and reason. It cannot swap direction.
3. Independent company Finance reviewer approves the exact version;
   cashbox custodian then records the real handover, with a finalized
   internal-only signed receipt/payment Evidence file owned by that
   proposal. Recorded voucher and original attachment are append-only.
4. Available cashbox balance is calculated from exactly the approved
   opening amount (zero) plus recorded customer receipts minus recorded
   supplier payouts. A supplier payout over physical cash balance fails.
5. Customer receivable/supplier payable outstanding counts both confirmed
   imported bank allocation and actual signed physical cash voucher.
   Source and cashbox row locks serialize concurrent writers, with
   an AFTER trigger that rejects cross-bank/cash double allocation.
   An AP source reversed after actual supplier cash payout fails closed.
6. Typed KMP native clients cover opening, approvals, cashier balance,
   creating vouchers, evidence-backed recording and status; these are
   **transport components, not role-native Windows/Android screens**.

## Default-OFF flag and authority properties

- `hiltech.finance.cashbox.enabled=false`
- `hiltech.finance.cashbox.opening-policy-key`
- `hiltech.finance.cashbox.preparer-authority-key`
- `hiltech.finance.cashbox.reader-authority-key`
- `hiltech.finance.cashbox.voucher-policy-key`
- `hiltech.finance.cashbox.voucher-preparer-authority-key`
- `hiltech.finance.cashbox.voucher-operator-authority-key`

Real approved company policy/authority bindings and authorized company
cashboxes must be configured; never guess or seed role identities.
The cashbox custodian is the recorded physical voucher operator and
cannot be its preparer or approver.

## Deployment order and test evidence

Apply `database/phase8/pending_cashbox_vouchers.sql` **AFTER**
`pending_ap_ar_cash_allocation.sql` and **BEFORE**
`pending_ap_ar_source_balance.sql`. Do not give these migrations a
historically conflicting Flyway number. DevOps must integrate all
stacked Phase 8 migrations with other main changes.

Dedicated CI:
- `FinancePhysicalCashPostgresContractTest` (source-bound approved
  zero-cashbox creation, no forged payment, immutable opening);
- `FinancePhysicalCashReviewVersionTest` (source, reviewer and
  amount drift invalidates signature);
- `FinancePhysicalCashApiClientTest` (authenticated native calls;
  never mislabels physical handover as bank transfer);
- `FinanceApArBalancePostgresContractTest` (bank + physical voucher
  combines under original, unaltered invoice source);
- full shared client desktop tests and Windows/Android compilation.

Test runs must be tied to exact head SHA; a previous green 8U build
does not accept this code automatically. Fixture cashbox opening is a
positive PostgreSQL test; live company cashiers and journal sources
are **not** acceptance-tested end-to-end here.

## Still required for full Finance OPERABLE sign-off

- **Real company cash opening inventory** greater than zero (currently
  strictly forbidden) must have its own signed two-person counted
  opening policy and evidence. Do not quietly inject existing till cash.
- Physical cashbox daily count / short-over discrepancy, audited
  count corrections and shifts/transfer/deposit source events; safe
  period close and bank-to-cash transfers are not yet implemented.
- Actual AP and AR positive receipt/payment fixtures, cross-medium
  partial payments and real concurrent cashier+bank workers must pass,
  along with employee cash reimbursements/funding where company actually
  uses cash and authentic approved internal expense classifications.
- Signed bank reconciliation, credit notes, AR reversals, cash
  mis-post corrections, VAT/invoice numbering, employee liability GL
  classification, and real company finance authorities remain external
  accounting policy or further engineering. These are not automatic
  side effects of voucher recording.
- DevOps native integration, staged Flyway migrations, permission-driven
  desktop/Android UI, unified CI, on-device end-to-end and company
  acceptance. Feature stays OFF until accepted.

**Do not mark Phase 8 complete or promote the Finance feature flags
based only on branch CI.** No bank or cash payment is initiated by an
unapproved or unfinalized voucher, and bank-statement SHA-256 matching is
not bank-issued authenticity proof.
