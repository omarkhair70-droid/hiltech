-- Employee custody and paid-cost accounting uses the same immutable GL.
-- The source evidence and approvals remain in their existing registers.
ALTER TABLE finance_gl_account DROP CONSTRAINT finance_gl_account_account_kind_check;
ALTER TABLE finance_gl_account ADD CONSTRAINT finance_gl_account_account_kind_check CHECK(account_kind IN(
 'SUPPLIER_PAYABLE','EXPENSE','INVENTORY_ASSET','FIXED_ASSET','TAX_RECOVERABLE',
 'OTHER_ASSET','CUSTOMER_RECEIVABLE','SALES_REVENUE','TAX_PAYABLE','BANK_ASSET','CASH_ASSET',
 'EMPLOYEE_ADVANCE_ASSET','EMPLOYEE_IMPREST_ASSET'));

CREATE OR REPLACE FUNCTION finance_ap_no_treasury_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=NEW.organization_id
    AND a.id=NEW.gl_account_id AND a.account_kind IN('BANK_ASSET','CASH_ASSET','EMPLOYEE_ADVANCE_ASSET','EMPLOYEE_IMPREST_ASSET'))
 THEN RAISE EXCEPTION 'Use the recorded settlement or employee source for a custody posting' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;

ALTER TABLE finance_settlement_gl_journal
 DROP CONSTRAINT finance_settlement_gl_journal_source_type_check,
 DROP CONSTRAINT finance_settlement_gl_journal_source_kind_check,
 ALTER COLUMN source_journal_id DROP NOT NULL,
 ADD COLUMN employee_funding_record_id uuid,
 ADD COLUMN expense_claim_id uuid,
 ADD COLUMN accounting_treatment_ref varchar(240),
 ADD CONSTRAINT finance_settlement_gl_journal_source_type_check CHECK(source_type IN(
   'BANK_ALLOCATION','CASH_VOUCHER','BANK_VOID','EMPLOYEE_FUNDING','EMPLOYEE_RETURN','IMPREST_EXPENSE','EXPENSE_REIMBURSEMENT')),
 ADD CONSTRAINT finance_settlement_gl_journal_source_kind_check CHECK(source_kind IN('AP','AR','EA','EI','ER')),
 ADD CONSTRAINT finance_settlement_gl_employee_funding_fk FOREIGN KEY(employee_funding_record_id,organization_id)
   REFERENCES finance_employee_funding_record(id,organization_id) ON DELETE RESTRICT,
 ADD CONSTRAINT finance_settlement_gl_expense_claim_fk FOREIGN KEY(expense_claim_id,organization_id)
   REFERENCES finance_expense_claim_draft(id,organization_id) ON DELETE RESTRICT,
 ADD CONSTRAINT finance_settlement_gl_source_scope_check CHECK(
    (source_type IN('BANK_ALLOCATION','CASH_VOUCHER','BANK_VOID') AND source_kind IN('AP','AR')
       AND source_journal_id IS NOT NULL AND employee_funding_record_id IS NULL AND expense_claim_id IS NULL)
 OR (source_type IN('EMPLOYEE_FUNDING','EMPLOYEE_RETURN','IMPREST_EXPENSE') AND source_kind IN('EA','EI')
       AND source_journal_id IS NULL AND employee_funding_record_id IS NOT NULL AND expense_claim_id IS NULL)
 OR (source_type='EXPENSE_REIMBURSEMENT' AND source_kind='ER'
       AND source_journal_id IS NULL AND employee_funding_record_id IS NULL AND expense_claim_id IS NOT NULL)),
 ADD CONSTRAINT finance_settlement_gl_cost_treatment_check CHECK(
    (source_type IN('IMPREST_EXPENSE','EXPENSE_REIMBURSEMENT') AND accounting_treatment_ref IS NOT NULL
       AND length(btrim(accounting_treatment_ref)) BETWEEN 8 AND 240)
 OR (source_type NOT IN('IMPREST_EXPENSE','EXPENSE_REIMBURSEMENT') AND accounting_treatment_ref IS NULL));

CREATE VIEW finance_settlement_gl_source_v2 AS
WITH funding AS (
 SELECT f.*,CASE WHEN f.draft_kind='EMPLOYEE_ADVANCE' THEN 'EA' ELSE 'EI' END AS custody_kind,
        CASE WHEN f.draft_kind='EMPLOYEE_ADVANCE' THEN 'EMPLOYEE_ADVANCE_ASSET' ELSE 'EMPLOYEE_IMPREST_ASSET' END AS custody_account_kind,
        p.display_name AS employee_name
 FROM finance_employee_funding_record f
 JOIN employee e ON e.id=f.employee_id AND e.organization_id=f.organization_id
 JOIN person p ON p.id=e.person_id
)
SELECT s.*,NULL::uuid AS employee_funding_record_id,NULL::uuid AS expense_claim_id,NULL::varchar AS required_debit_kind
 FROM finance_settlement_gl_source s
UNION ALL
SELECT f.organization_id,'EMPLOYEE_FUNDING',f.id,f.custody_kind,NULL::uuid,f.approval_request_id,
 f.amount,f.currency,f.recorded_at,'BANK',b.account_id,a.bank_name || ' · ' || a.masked_account_reference,
 f.employee_id,f.employee_name,NULL::uuid,NULL::uuid,g.id,f.id,NULL::uuid,f.custody_account_kind
FROM funding f
JOIN finance_bank_statement_intake_line b ON b.organization_id=f.organization_id AND b.id=f.bank_statement_line_id
JOIN finance_bank_account_register a ON a.organization_id=b.organization_id AND a.id=b.account_id
LEFT JOIN finance_gl_account g ON g.organization_id=b.organization_id AND g.treasury_bank_account_id=b.account_id
 AND g.currency=f.currency AND g.state='APPROVED'
UNION ALL
SELECT r.organization_id,CASE WHEN r.action='IMPREST_RECEIPTS_ACCEPTED' THEN 'IMPREST_EXPENSE' ELSE 'EMPLOYEE_RETURN' END,
 r.id,f.custody_kind,NULL::uuid,r.approval_request_id,r.amount,r.currency,r.recorded_at,
 CASE WHEN r.action='IMPREST_RECEIPTS_ACCEPTED' THEN 'EVIDENCE' ELSE 'BANK' END,b.account_id,
 CASE WHEN r.action='IMPREST_RECEIPTS_ACCEPTED' THEN 'Accepted custody receipts' ELSE a.bank_name || ' · ' || a.masked_account_reference END,
 r.employee_id,f.employee_name,NULL::uuid,g.id,j.debit_account_id,f.id,NULL::uuid,
 CASE WHEN r.action='IMPREST_RECEIPTS_ACCEPTED' THEN 'COST' ELSE NULL END
FROM finance_employee_settlement_record r
JOIN funding f ON f.organization_id=r.organization_id AND f.id=r.funding_record_id
LEFT JOIN finance_settlement_gl_journal j ON j.organization_id=f.organization_id AND j.source_type='EMPLOYEE_FUNDING' AND j.source_id=f.id
LEFT JOIN finance_bank_statement_intake_line b ON b.organization_id=r.organization_id AND b.id=r.bank_statement_line_id
LEFT JOIN finance_bank_account_register a ON a.organization_id=b.organization_id AND a.id=b.account_id
LEFT JOIN finance_gl_account g ON g.organization_id=b.organization_id AND g.treasury_bank_account_id=b.account_id
 AND g.currency=r.currency AND g.state='APPROVED'
UNION ALL
SELECT r.organization_id,'EXPENSE_REIMBURSEMENT',r.id,'ER',NULL::uuid,r.approval_request_id,
 r.amount,r.currency,r.recorded_at,'BANK',b.account_id,a.bank_name || ' · ' || a.masked_account_reference,
 r.employee_id,p.display_name,NULL::uuid,NULL::uuid,g.id,NULL::uuid,r.expense_draft_id,'COST'
FROM finance_expense_reimbursement_record r
JOIN employee e ON e.id=r.employee_id AND e.organization_id=r.organization_id
JOIN person p ON p.id=e.person_id
JOIN finance_bank_statement_intake_line b ON b.organization_id=r.organization_id AND b.id=r.bank_statement_line_id
JOIN finance_bank_account_register a ON a.organization_id=b.organization_id AND a.id=b.account_id
LEFT JOIN finance_gl_account g ON g.organization_id=b.organization_id AND g.treasury_bank_account_id=b.account_id
 AND g.currency=r.currency AND g.state='APPROVED';

CREATE OR REPLACE FUNCTION finance_settlement_gl_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s finance_settlement_gl_source_v2%ROWTYPE;
        p finance_gl_period%ROWTYPE;
        original_id uuid;
BEGIN
 -- Serialize with an allocation correction, which locks the original match
 -- before the period. Closing locks the same period row as every GL writer.
 IF NEW.source_type='BANK_ALLOCATION' THEN
   PERFORM id FROM finance_cash_allocation_record WHERE organization_id=NEW.organization_id AND id=NEW.source_id FOR UPDATE;
 ELSIF NEW.source_type='BANK_VOID' THEN
   SELECT original_record_id INTO original_id FROM finance_cash_allocation_void_record
     WHERE organization_id=NEW.organization_id AND id=NEW.source_id;
   PERFORM id FROM finance_cash_allocation_record WHERE organization_id=NEW.organization_id AND id=original_id FOR UPDATE;
 END IF;
 SELECT * INTO p FROM finance_gl_period WHERE organization_id=NEW.organization_id AND id=NEW.period_id FOR UPDATE;
 IF NOT FOUND OR p.state<>'OPEN' OR (NEW.posted_at AT TIME ZONE 'UTC')::date NOT BETWEEN p.date_start AND p.date_end
 OR EXISTS(SELECT 1 FROM finance_gl_period_close c WHERE c.organization_id=NEW.organization_id AND c.period_id=p.id)
 THEN RAISE EXCEPTION 'Settlement requires a current open accounting period' USING ERRCODE='23514'; END IF;
 SELECT * INTO s FROM finance_settlement_gl_source_v2 WHERE organization_id=NEW.organization_id
    AND source_type=NEW.source_type AND source_id=NEW.source_id;
 IF NOT FOUND OR s.source_kind IS DISTINCT FROM NEW.source_kind
 OR s.source_journal_id IS DISTINCT FROM NEW.source_journal_id
 OR s.source_approval_id IS DISTINCT FROM NEW.source_approval_id
 OR s.original_settlement_id IS DISTINCT FROM NEW.original_settlement_id
 OR s.employee_funding_record_id IS DISTINCT FROM NEW.employee_funding_record_id
 OR s.expense_claim_id IS DISTINCT FROM NEW.expense_claim_id
 OR (s.required_debit_kind IS NULL AND s.debit_account_id IS DISTINCT FROM NEW.debit_account_id)
 OR (s.required_debit_kind IS NOT NULL AND NOT EXISTS(SELECT 1 FROM finance_gl_account a
     WHERE a.organization_id=NEW.organization_id AND a.id=NEW.debit_account_id AND a.state='APPROVED'
       AND (a.currency IS NULL OR a.currency=NEW.currency)
       AND (a.account_kind=s.required_debit_kind OR (s.required_debit_kind='COST'
           AND a.account_kind IN('EXPENSE','INVENTORY_ASSET','FIXED_ASSET','OTHER_ASSET')))))
 OR s.credit_account_id IS DISTINCT FROM NEW.credit_account_id
 OR (s.required_debit_kind='COST' AND (NEW.accounting_treatment_ref IS NULL OR length(btrim(NEW.accounting_treatment_ref))<8))
 OR s.amount IS DISTINCT FROM NEW.amount OR s.currency IS DISTINCT FROM NEW.currency
 OR s.source_recorded_at IS DISTINCT FROM NEW.source_recorded_at
 OR NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=NEW.organization_id
     AND a.id=NEW.debit_account_id AND a.state='APPROVED' AND (a.currency IS NULL OR a.currency=NEW.currency))
 OR NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=NEW.organization_id
     AND a.id=NEW.credit_account_id AND a.state='APPROVED' AND (a.currency IS NULL OR a.currency=NEW.currency))
 THEN RAISE EXCEPTION 'Settlement must match exact recorded source and approved accounts' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;

-- Historical snapshots remain byte-compatible for pre-existing source types.
CREATE OR REPLACE VIEW finance_gl_period_close_source AS
SELECT p.id AS period_id,p.organization_id,p.date_start,p.date_end,p.state,e.journal_count,
 encode(sha256(convert_to(concat_ws('|','FINANCE_GL_PERIOD_CLOSE_SOURCE_V3',
    p.id::text,p.organization_id::text,p.date_start::text,p.date_end::text,p.state,
    e.journal_count::text,COALESCE(e.event_fingerprints,'EMPTY')),'UTF8')),'hex') AS snapshot_sha256
FROM finance_gl_period p CROSS JOIN LATERAL (
 SELECT COUNT(*)::bigint AS journal_count,string_agg(x.fingerprint,'|' ORDER BY x.fingerprint) AS event_fingerprints
 FROM (
 SELECT concat_ws(':','AP',j.id::text,j.payable_candidate_id::text,j.gross_amount::text,j.currency,j.ledger_fingerprint_sha256) AS fingerprint
 FROM finance_ap_posted_journal j WHERE j.organization_id=p.organization_id AND j.fiscal_period_id=p.id
 UNION ALL
 SELECT concat_ws(':','AP_REV',r.id::text,r.original_journal_id::text,r.gross_amount::text,r.currency,r.source_ledger_fingerprint_sha256)
 FROM finance_ap_journal_reversal r WHERE r.organization_id=p.organization_id AND r.target_period_id=p.id
 UNION ALL
 SELECT concat_ws(':','AR',a.id::text,a.issued_invoice_id::text,a.gross_amount::text,a.currency,a.ledger_fingerprint_sha256)
 FROM finance_ar_posted_journal a WHERE a.organization_id=p.organization_id AND a.fiscal_period_id=p.id
 UNION ALL
 SELECT concat_ws(':','SETTLEMENT',s.id::text,s.source_type,s.source_id::text,s.source_journal_id::text,
    s.source_approval_id::text,s.debit_account_id::text,s.credit_account_id::text,s.amount::text,s.currency,
    COALESCE(s.original_settlement_id::text,'-'),
    s.employee_funding_record_id::text,s.expense_claim_id::text,s.accounting_treatment_ref)
 FROM finance_settlement_gl_journal s WHERE s.organization_id=p.organization_id AND s.period_id=p.id
 ) x
) e;


