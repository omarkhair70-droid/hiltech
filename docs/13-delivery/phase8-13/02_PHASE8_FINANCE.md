# P8 Finance owner / branch contract
Own: finance domain, ledger, protected finance query/API, native Finance screens and tests. Do not write Procurement, People or Payroll source tables.
8A exact P7 invoice-match -> one Payable; actual commercial billing milestone/client invoice -> Receivable; versioned amount/currency and source ID.
8B employee Expense vs Advance vs FinancialImprest: three distinct objects; approval, funding proof, receipts, append-only cash custody/spend/settlement/return/shortage/overage. No auto payroll deduction for an imprest.
8C payment instruction, exact amount/beneficiary/source/version approval, duplicate protection, UNKNOWN external result never blind-retried; bank API not built without actual supported bank path.
8D payment result, partial allocation, returned/reversed, reconciliation, exception queue, project finance aggregation.
Reuse People, Project, P7 finance handoff, Approval, Evidence, Audit, Inbox. Hard gate actual accountant/legal/tax/currency/bank signatory/settlement and per-field authority. Real Windows Finance flow and employee limited self-service; negative authorization and replay tests.
Publish: immutable approved Advance deduction and authoritative payment-result interfaces to Payroll; typed client billing contract with Sales; no cross-module SQL writes.
