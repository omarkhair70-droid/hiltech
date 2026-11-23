-- Phase 8 project finance source view. UNNUMBERED / read-only / OFF.
-- Show only authoritative direct project joins; do not invent margin,
-- cash flow, company tax, bank reconciliation or allocation attribution.
CREATE VIEW finance_project_source_event AS
SELECT j.organization_id, c.project_id,
       'AR_RECEIVABLE'::varchar(48) AS source_kind,
       j.id AS source_event_id,
       j.currency,j.gross_amount AS original_amount,
       j.posted_at AS event_at,
       true AS gl_recognized,false AS confirmed_bank_allocation,
       false AS reversed,
       'GL_RECEIVABLE_POSTED_BANK_ALLOCATION_REPORTED_SEPARATELY'::varchar(96)
         AS source_status
FROM finance_ar_posted_journal j
JOIN finance_ar_issued_invoice i
  ON i.id=j.issued_invoice_id AND i.organization_id=j.organization_id
JOIN finance_ar_invoice_candidate c
  ON c.id=i.candidate_id AND c.organization_id=i.organization_id
JOIN project pr ON pr.id=c.project_id AND pr.organization_id=j.organization_id

UNION ALL

SELECT j.organization_id,w.project_id,
       'AP_PAYABLE'::varchar(48) AS source_kind,
       j.id AS source_event_id,
       j.currency,j.gross_amount AS original_amount,
       j.posted_at AS event_at,
       (rv.id IS NULL) AS gl_recognized,
       false AS confirmed_bank_allocation,
       (rv.id IS NOT NULL) AS reversed,
       CASE WHEN rv.id IS NULL
            THEN 'GL_PAYABLE_POSTED_BANK_ALLOCATION_REPORTED_SEPARATELY'
            ELSE 'AP_REVERSED_SOURCE_EXCLUDED_FROM_NET_LIABILITY'
       END::varchar(96) AS source_status
FROM finance_ap_posted_journal j
JOIN finance_payable_candidate payable
  ON payable.id=j.payable_candidate_id
 AND payable.organization_id=j.organization_id
JOIN procurement_purchase_order po
  ON po.id=payable.purchase_order_id
 AND po.organization_id=payable.organization_id
JOIN procurement_po_candidate pc
  ON pc.id=po.candidate_id AND pc.organization_id=po.organization_id
JOIN procurement_purchase_requirement req
  ON req.id=pc.purchase_requirement_id
 AND req.organization_id=po.organization_id
JOIN work_order w ON w.id=req.work_order_id
JOIN project pr ON pr.id=w.project_id AND pr.organization_id=j.organization_id
LEFT JOIN finance_ap_journal_reversal rv
  ON rv.original_journal_id=j.id AND rv.organization_id=j.organization_id

UNION ALL

SELECT e.organization_id,d.project_id,
       'EMPLOYEE_PERSONAL_EXPENSE_REIMBURSEMENT'::varchar(48) AS source_kind,
       e.id AS source_event_id,
       e.currency,e.amount AS original_amount,
       e.recorded_at AS event_at,
       false AS gl_recognized,true AS confirmed_bank_allocation,
       false AS reversed,
       'BANK_DEBIT_ALLOCATED_EMPLOYEE_EXPENSE_NOT_GL_RECOGNIZED'::varchar(96)
         AS source_status
FROM finance_expense_reimbursement_record e
JOIN finance_expense_claim_draft d
  ON d.id=e.expense_draft_id AND d.organization_id=e.organization_id
JOIN project pr ON pr.id=d.project_id AND pr.organization_id=e.organization_id
WHERE d.project_id IS NOT NULL;

-- AP/AR source gross is not project profit; the cash ledger has independent
-- partial allocations and bank statements can remain unreconciled. Employee
-- reimbursements are distinct cash evidence, not automatic GL expense.
