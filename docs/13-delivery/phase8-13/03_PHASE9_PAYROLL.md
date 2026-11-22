# P9 Payroll owner / branch contract
9A payroll period, employee eligibility/employment and compensation/time/approved input snapshots with source versions; identify missing data without guessing Egypt statutory rules.
9B policy-configured calculation, exception review, deterministic rounding, controlled adjustment, immutable run versions and ApprovalEngine binding; payroll accountant and owner authorities real, not hardcoded.
9C Finance-issued payment batch reference, individual SUCCESS/FAILED/RETURNED/UNKNOWN results, reconciliation, private employee payslip, correction run, final pay/offboarding.
Reuse existing People, Employment, WorkforceAssignment, Offboarding, Approval, approved Phase8 Advance payroll deduction. Payroll owns no Advance balance and no bank payment ledger. Start 9A while Finance builds; block release of 9B/9C pending HR/accountant validated statutory deductions, compensation privacy, banking and Finance contracts.
Tests: employee cannot see coworker pay, terminated employee snapshot, changed input after approval, partial/unknown transfer, deduction limits, correction replay.
