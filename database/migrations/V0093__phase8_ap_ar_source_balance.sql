-- Phase 8U: calculated cash allocation balances, not bank reconciliation.
-- These figures are backed ONLY by recorded source journals and approved
-- bank statement allocations. All values preserve their original currency.
-- No inferred payroll expenses, VAT rates or exchange rates.
CREATE VIEW finance_ap_ar_source_balance AS
WITH recognized_movements AS (
 SELECT organization_id,source_kind,source_journal_id,
        allocated_amount AS amount
 FROM finance_cash_allocation_effective
 UNION ALL
 SELECT organization_id,source_kind,source_journal_id,
        amount
 FROM finance_cash_voucher_record
), cash_used AS (
 SELECT organization_id,source_kind,source_journal_id,
        sum(amount) AS allocated_amount
 FROM recognized_movements
 GROUP BY organization_id,source_kind,source_journal_id
)
SELECT j.organization_id, 'AP'::varchar(2) AS source_kind,
       j.id AS journal_id, p.supplier_organization_id AS counterparty_id,
       j.currency,j.posted_at AS recognized_at,
       NULL::date AS contractual_due_date,
       j.gross_amount,
       coalesce(c.allocated_amount,0::numeric) AS allocated_amount,
       CASE WHEN r.id IS NOT NULL THEN NULL::numeric
         ELSE j.gross_amount-coalesce(c.allocated_amount,0::numeric)
       END AS outstanding_amount,
       (r.id IS NOT NULL) AS reversed,
       CASE WHEN r.id IS NOT NULL AND c.allocated_amount>0
            THEN 'REVERSED_WITH_BANK_ALLOCATION_EXCEPTION'
            WHEN r.id IS NOT NULL THEN 'REVERSED_NO_OUTSTANDING'
            WHEN c.allocated_amount IS NULL THEN 'UNALLOCATED'
            WHEN c.allocated_amount<j.gross_amount THEN 'PARTIALLY_ALLOCATED'
            WHEN c.allocated_amount=j.gross_amount
                 THEN 'ALLOCATED_NOT_BANK_RECONCILED'
            ELSE 'OVERALLOCATED_EXCEPTION'
       END AS accounting_state
FROM finance_ap_posted_journal j
JOIN finance_ap_posting_proposal p ON p.id=j.proposal_id
 AND p.organization_id=j.organization_id
LEFT JOIN cash_used c ON c.organization_id=j.organization_id
 AND c.source_kind='AP' AND c.source_journal_id=j.id
LEFT JOIN finance_ap_journal_reversal r ON r.organization_id=j.organization_id
 AND r.original_journal_id=j.id
UNION ALL
SELECT j.organization_id, 'AR'::varchar(2) AS source_kind,
       j.id AS journal_id, i.customer_organization_id AS counterparty_id,
       j.currency,j.posted_at AS recognized_at,
       i.due_date AS contractual_due_date,j.gross_amount,
       coalesce(c.allocated_amount,0::numeric) AS allocated_amount,
       j.gross_amount-coalesce(c.allocated_amount,0::numeric) AS outstanding_amount,
       false AS reversed,
       CASE WHEN c.allocated_amount IS NULL THEN 'UNALLOCATED'
            WHEN c.allocated_amount<j.gross_amount THEN 'PARTIALLY_ALLOCATED'
            WHEN c.allocated_amount=j.gross_amount
                 THEN 'ALLOCATED_NOT_BANK_RECONCILED'
            ELSE 'OVERALLOCATED_EXCEPTION'
       END AS accounting_state
FROM finance_ar_posted_journal j
JOIN finance_ar_issued_invoice i ON i.id=j.issued_invoice_id
 AND i.organization_id=j.organization_id
LEFT JOIN cash_used c ON c.organization_id=j.organization_id
 AND c.source_kind='AR' AND c.source_journal_id=j.id;

-- Source outstanding is NOT a bank account balance.
-- Due dates are NULL for AP until sourced from independently verified
-- supplier payment terms; no invented AP aging is provided.
