-- Phase 8U: Read-only variance per independently reviewed bank statement line.
-- "FULLY_ALLOCATED" does NOT mean bank reconciled or bank money moved.
CREATE VIEW finance_bank_allocation_exception AS
WITH assigned AS (
 SELECT organization_id,bank_statement_line_id,
        sum(allocated_amount) AS applied
 FROM finance_cash_allocation_record
 GROUP BY organization_id,bank_statement_line_id
)
SELECT b.organization_id,b.intake_id,b.account_id,
       b.id AS bank_line_id,b.external_transaction_id,b.booked_on,
       b.flow,b.currency,b.amount AS statement_amount,
       coalesce(a.applied,0::numeric) AS allocated_amount,
       b.amount-coalesce(a.applied,0::numeric) AS unallocated_amount,
       CASE WHEN a.applied IS NULL THEN 'UNALLOCATED_BANK_LINE'
            WHEN a.applied=b.amount THEN 'FULLY_ALLOCATED_NOT_RECONCILED'
            WHEN a.applied<b.amount THEN 'PARTIAL_ALLOCATION_EXCEPTION'
            ELSE 'OVERALLOCATED_EXCEPTION'
       END AS exception_state
FROM finance_bank_statement_intake_line b
LEFT JOIN assigned a ON a.organization_id=b.organization_id
 AND a.bank_statement_line_id=b.id;

-- No fuzzy matching, no inferred bank fees, no automatic reconciliation.
-- Missing GL adjustment events and independent signed reconciliation review.
