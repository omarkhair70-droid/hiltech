-- Source-bound compound payroll accrual. One immutable journal; amounts are
-- derived from rounded frozen components, never supplied by the allocator.
-- New treatment is limited to third-party deduction obligations and costs
-- recognized for the first time. Advance recovery/previously posted costs
-- require their own source-bound clearing workflow, not a duplicate cost.
ALTER TABLE finance_gl_account DROP CONSTRAINT finance_gl_account_account_kind_check;
ALTER TABLE finance_gl_account ADD CONSTRAINT finance_gl_account_account_kind_check CHECK(account_kind IN(
 'SUPPLIER_PAYABLE','EXPENSE','INVENTORY_ASSET','FIXED_ASSET','TAX_RECOVERABLE',
 'OTHER_ASSET','CUSTOMER_RECEIVABLE','SALES_REVENUE','TAX_PAYABLE','BANK_ASSET','CASH_ASSET',
 'EMPLOYEE_ADVANCE_ASSET','EMPLOYEE_IMPREST_ASSET','PAYROLL_EXPENSE','PAYROLL_PAYABLE','PAYROLL_DEDUCTION_PAYABLE'));

CREATE OR REPLACE FUNCTION finance_ap_no_treasury_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=NEW.organization_id
    AND a.id=NEW.gl_account_id AND a.account_kind IN('BANK_ASSET','CASH_ASSET',
        'EMPLOYEE_ADVANCE_ASSET','EMPLOYEE_IMPREST_ASSET','PAYROLL_EXPENSE','PAYROLL_PAYABLE','PAYROLL_DEDUCTION_PAYABLE'))
 THEN RAISE EXCEPTION 'Use the recorded settlement, employee or payroll source for this posting' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;


ALTER TABLE finance_payroll_gl_journal ADD COLUMN component_allocations jsonb NOT NULL DEFAULT '[]'::jsonb
 CHECK(jsonb_typeof(component_allocations)='array' AND jsonb_array_length(component_allocations)<=30
   AND (entry_type='PAYROLL_ACCRUAL' OR component_allocations='[]'::jsonb));

CREATE VIEW finance_payroll_gl_component_group AS
 SELECT r.organization_id,r.id AS run_version_id,c.component_kind,c.source_domain,c.component_code,
   sum(c.amount)::numeric(24,6) AS amount
 FROM payroll_run_version r JOIN payroll_run_employee_line l ON l.run_version_id=r.id
 JOIN payroll_run_component_ref c ON c.run_employee_line_id=l.id
 WHERE c.component_kind IN('DEDUCTION','REIMBURSEMENT')
 GROUP BY r.organization_id,r.id,c.component_kind,c.source_domain,c.component_code;

CREATE FUNCTION finance_payroll_gl_components_complete(org uuid,run_id uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM payroll_run_version r
   WHERE r.organization_id=org AND r.id=run_id AND r.gross>0
   AND EXISTS(SELECT 1 FROM payroll_run_employee_line l WHERE l.run_version_id=r.id)
   AND NOT EXISTS(
     SELECT 1 FROM payroll_run_employee_line l
     CROSS JOIN LATERAL (SELECT
       coalesce(sum(c.amount) FILTER (WHERE c.component_kind='EARNING'),0) gross,
       coalesce(sum(c.amount) FILTER (WHERE c.component_kind='DEDUCTION'),0) deductions,
       coalesce(sum(c.amount) FILTER (WHERE c.component_kind='REIMBURSEMENT'),0) reimbursements
       FROM payroll_run_component_ref c WHERE c.run_employee_line_id=l.id) s
     WHERE l.run_version_id=r.id AND (l.gross<>s.gross OR l.deductions<>s.deductions OR l.reimbursements<>s.reimbursements))
   AND (SELECT coalesce(sum(l.gross),0) FROM payroll_run_employee_line l WHERE l.run_version_id=r.id)=r.gross
   AND (SELECT coalesce(sum(l.deductions),0) FROM payroll_run_employee_line l WHERE l.run_version_id=r.id)=r.deductions
   AND (SELECT coalesce(sum(l.reimbursements),0) FROM payroll_run_employee_line l WHERE l.run_version_id=r.id)=r.reimbursements
   AND (SELECT coalesce(sum(l.net),0) FROM payroll_run_employee_line l WHERE l.run_version_id=r.id)=r.net)
$$;

CREATE FUNCTION finance_payroll_gl_allocations_valid(org uuid,run_id uuid,ccy varchar,allocations jsonb)
 RETURNS boolean LANGUAGE plpgsql STABLE AS $$
DECLARE expected_count integer; item jsonb; account uuid;
BEGIN
 IF allocations IS NULL OR jsonb_typeof(allocations)<>'array' OR jsonb_array_length(allocations)>30 THEN RETURN false; END IF;
 SELECT count(*) INTO expected_count FROM finance_payroll_gl_component_group g
   WHERE g.organization_id=org AND g.run_version_id=run_id;
 IF expected_count<>jsonb_array_length(allocations) THEN RETURN false; END IF;
 FOR item IN SELECT value FROM jsonb_array_elements(allocations) LOOP
   IF jsonb_typeof(item)<>'object' THEN RETURN false; END IF;
   IF (SELECT count(*) FROM jsonb_object_keys(item))<>5
      OR NOT (item ?& ARRAY['kind','domain','code','accountId','reference'])
      OR EXISTS(SELECT 1 FROM jsonb_each(item) f WHERE jsonb_typeof(f.value)<>'string')
      OR item->>'kind' NOT IN('DEDUCTION','REIMBURSEMENT')
      OR length(btrim(item->>'reference')) NOT BETWEEN 8 AND 240
      OR item->>'reference'<>btrim(item->>'reference')
      OR item->>'reference' ~ '[[:cntrl:]]'
      OR item->>'accountId' !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
      OR NOT EXISTS(SELECT 1 FROM finance_payroll_gl_component_group g WHERE g.organization_id=org AND g.run_version_id=run_id
        AND g.component_kind=item->>'kind' AND g.source_domain=item->>'domain' AND g.component_code=item->>'code')
   THEN RETURN false; END IF;
   account:=(item->>'accountId')::uuid;
   IF NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=org AND a.id=account
      AND a.state='APPROVED' AND (a.currency IS NULL OR a.currency=ccy)
      AND ((item->>'kind'='DEDUCTION' AND a.account_kind IN('PAYROLL_DEDUCTION_PAYABLE','TAX_PAYABLE'))
        OR (item->>'kind'='REIMBURSEMENT' AND a.account_kind='EXPENSE')))
   THEN RETURN false; END IF;
 END LOOP;
 RETURN (SELECT count(DISTINCT (i->>'kind',i->>'domain',i->>'code')) FROM jsonb_array_elements(allocations) i)=expected_count;
END; $$;

CREATE OR REPLACE FUNCTION finance_payroll_gl_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
 r payroll_run_version%ROWTYPE;
 rel payroll_run_release%ROWTYPE;
 payment_batch finance_payroll_payment_batch%ROWTYPE;
 e finance_payroll_result_event%ROWTYPE;
 i finance_payroll_instruction%ROWTYPE;
 prior finance_payroll_result_event%ROWTYPE;
 accrual finance_payroll_gl_journal%ROWTYPE;
 original finance_payroll_gl_journal%ROWTYPE;
 fiscal finance_gl_period%ROWTYPE;
 bank_account uuid;
BEGIN
 IF NOT finance_payroll_current_authority(NEW.organization_id,NEW.posted_by_user_id,'PREPARE')
 THEN RAISE EXCEPTION 'Current finance payroll preparation authority required' USING ERRCODE='23514'; END IF;
 -- Match the lock order of payroll result writers: instruction, payroll period,
 -- accounting period. A result correction cannot race a posting of its source.
 IF NEW.source_event_id IS NOT NULL THEN
   SELECT * INTO e FROM finance_payroll_result_event
     WHERE organization_id=NEW.organization_id AND id=NEW.source_event_id;
   IF NOT FOUND THEN RAISE EXCEPTION 'Recorded payroll result required' USING ERRCODE='23514'; END IF;
   SELECT * INTO i FROM finance_payroll_instruction
     WHERE organization_id=NEW.organization_id AND id=e.instruction_id FOR UPDATE;
   IF NOT FOUND OR i.run_version_id<>NEW.run_version_id
   THEN RAISE EXCEPTION 'Payroll instruction and run differ' USING ERRCODE='23514'; END IF;
 END IF;
 PERFORM 1 FROM payroll_period WHERE organization_id=NEW.organization_id AND id=NEW.payroll_period_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Payroll period unavailable' USING ERRCODE='23514'; END IF;
 SELECT * INTO r FROM payroll_run_version WHERE organization_id=NEW.organization_id AND id=NEW.run_version_id;
 SELECT * INTO rel FROM payroll_run_release WHERE organization_id=NEW.organization_id AND id=NEW.release_id;
 SELECT * INTO payment_batch FROM finance_payroll_payment_batch
   WHERE organization_id=NEW.organization_id AND run_version_id=NEW.run_version_id;
 IF r.id IS NULL OR rel.id IS NULL OR payment_batch.id IS NULL
 OR r.payroll_period_id<>NEW.payroll_period_id OR rel.run_version_id<>r.id
 OR rel.run_source_fingerprint<>r.source_fingerprint OR r.currency<>NEW.currency
 OR (i.id IS NOT NULL AND i.batch_id<>payment_batch.id)
 THEN RAISE EXCEPTION 'Exact released and finance-prepared payroll source required' USING ERRCODE='23514'; END IF;
 SELECT * INTO fiscal FROM finance_gl_period WHERE organization_id=NEW.organization_id AND id=NEW.period_id FOR UPDATE;
 IF NOT FOUND OR fiscal.state<>'OPEN'
 OR (NEW.posted_at AT TIME ZONE 'UTC')::date NOT BETWEEN fiscal.date_start AND fiscal.date_end
 OR EXISTS(SELECT 1 FROM finance_gl_period_close c WHERE c.organization_id=NEW.organization_id AND c.period_id=fiscal.id)
 THEN RAISE EXCEPTION 'Payroll posting requires an open accounting period' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=NEW.organization_id AND a.id=NEW.debit_account_id
    AND a.state='APPROVED' AND (a.currency IS NULL OR a.currency=NEW.currency))
 OR NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=NEW.organization_id AND a.id=NEW.credit_account_id
    AND a.state='APPROVED' AND (a.currency IS NULL OR a.currency=NEW.currency))
 THEN RAISE EXCEPTION 'Approved same-currency posting accounts required' USING ERRCODE='23514'; END IF;
 IF NEW.entry_type='PAYROLL_ACCRUAL' THEN
   IF r.gross<=0 OR NEW.amount<>r.gross
     OR NEW.source_recorded_at<>payment_batch.prepared_at
     OR EXISTS(SELECT 1 FROM payroll_run_correction c WHERE c.organization_id=NEW.organization_id AND c.replacement_run_version_id=r.id)
     OR EXISTS(SELECT 1 FROM payroll_run_version newer JOIN payroll_run_release release ON release.run_version_id=newer.id
       WHERE newer.organization_id=r.organization_id AND newer.payroll_period_id=r.payroll_period_id AND newer.generation>r.generation)
     OR NOT finance_payroll_gl_components_complete(r.organization_id,r.id)
     OR NOT finance_payroll_gl_allocations_valid(r.organization_id,r.id,NEW.currency,NEW.component_allocations)
     OR NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.id=NEW.debit_account_id AND a.account_kind='PAYROLL_EXPENSE')
     OR NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.id=NEW.credit_account_id AND a.account_kind='PAYROLL_PAYABLE')
   THEN RAISE EXCEPTION 'Accrual requires exact released components and approved accounting treatment' USING ERRCODE='23514'; END IF;
   RETURN NEW;
 END IF;
 IF NEW.component_allocations<>'[]'::jsonb
 THEN RAISE EXCEPTION 'Only accrual can contain component allocations' USING ERRCODE='23514'; END IF;
 SELECT * INTO accrual FROM finance_payroll_gl_journal j WHERE j.organization_id=NEW.organization_id
   AND j.run_version_id=r.id AND j.entry_type='PAYROLL_ACCRUAL';
 IF accrual.id IS NULL OR e.amount<>NEW.amount OR e.currency<>NEW.currency OR e.recorded_at<>NEW.source_recorded_at
 OR NEW.posted_at<accrual.posted_at
 THEN RAISE EXCEPTION 'Payroll settlement requires its posted accrual and exact result' USING ERRCODE='23514'; END IF;
 IF NEW.entry_type='PAYROLL_PAYMENT' THEN
   IF e.action<>'SUCCESS' OR e.outcome<>'SUCCESS' OR NEW.debit_account_id<>accrual.credit_account_id
   OR EXISTS(SELECT 1 FROM finance_payroll_result_event v WHERE v.organization_id=e.organization_id AND v.corrects_event_id=e.id)
   OR EXISTS(SELECT 1 FROM finance_payroll_gl_journal j JOIN finance_payroll_result_event older
       ON older.organization_id=j.organization_id AND older.id=j.source_event_id
       WHERE j.organization_id=NEW.organization_id AND j.entry_type='PAYROLL_PAYMENT'
       AND older.instruction_id=e.instruction_id AND older.version<e.version
       AND NOT EXISTS(SELECT 1 FROM finance_payroll_gl_journal reversal WHERE reversal.organization_id=j.organization_id
           AND reversal.original_journal_id=j.id))
   THEN RAISE EXCEPTION 'Payroll payment must be a current successful match without an unreversed prior posting' USING ERRCODE='23514'; END IF;
 ELSIF NEW.entry_type IN('PAYROLL_RETURN','PAYROLL_VOID') THEN
   SELECT * INTO prior FROM finance_payroll_result_event WHERE organization_id=e.organization_id
     AND instruction_id=e.instruction_id AND version=e.version-1;
   SELECT * INTO original FROM finance_payroll_gl_journal WHERE organization_id=NEW.organization_id
     AND id=NEW.original_journal_id AND source_event_id=prior.id AND entry_type='PAYROLL_PAYMENT';
   IF original.id IS NULL OR prior.action<>'SUCCESS' OR original.run_version_id<>r.id
     OR original.amount<>NEW.amount OR NEW.posted_at<original.posted_at
     OR NEW.credit_account_id<>original.debit_account_id
     OR (NEW.entry_type='PAYROLL_RETURN' AND (e.action<>'RETURNED' OR e.outcome<>'RETURNED'))
     OR (NEW.entry_type='PAYROLL_VOID' AND (e.action<>'VOID_MATCH' OR e.outcome<>'UNKNOWN'
       OR e.corrects_event_id IS DISTINCT FROM prior.id OR NEW.debit_account_id<>original.credit_account_id))
   THEN RAISE EXCEPTION 'Payroll return or void must match its exact posted payment' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.entry_type<>'PAYROLL_VOID' THEN
   SELECT a.id INTO bank_account FROM finance_bank_statement_intake_line b
     JOIN finance_gl_account a ON a.organization_id=b.organization_id AND a.treasury_bank_account_id=b.account_id
     WHERE b.organization_id=e.organization_id AND b.id=e.bank_statement_line_id
       AND b.amount=e.amount AND b.currency=e.currency AND a.state='APPROVED' AND a.currency=e.currency
       AND b.flow=(CASE WHEN NEW.entry_type='PAYROLL_PAYMENT' THEN 'DEBIT' ELSE 'CREDIT' END);
   IF bank_account IS NULL OR (NEW.entry_type='PAYROLL_PAYMENT' AND NEW.credit_account_id<>bank_account)
     OR (NEW.entry_type='PAYROLL_RETURN' AND NEW.debit_account_id<>bank_account)
   THEN RAISE EXCEPTION 'Exact approved bank ledger account required' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END; $$;

-- Existing two-line journals stay byte-for-byte equivalent. For accruals,
-- the liability is net pay, not gross, and each mapped component is a line.
CREATE OR REPLACE VIEW finance_payroll_gl_journal_line AS
 SELECT j.organization_id,j.id AS journal_id,1 AS line_number,j.debit_account_id AS gl_account_id,'DEBIT'::varchar AS side,j.amount
 FROM finance_payroll_gl_journal j
 UNION ALL
 SELECT j.organization_id,j.id,2,j.credit_account_id,'CREDIT'::varchar,
   (CASE WHEN j.entry_type='PAYROLL_ACCRUAL' THEN r.net ELSE j.amount END)::numeric(24,6)
 FROM finance_payroll_gl_journal j JOIN payroll_run_version r ON r.id=j.run_version_id AND r.organization_id=j.organization_id
 WHERE j.entry_type<>'PAYROLL_ACCRUAL' OR r.net>0
 UNION ALL
 SELECT j.organization_id,j.id,(2+i.ordinality)::integer,(i.value->>'accountId')::uuid,
   (CASE WHEN g.component_kind='DEDUCTION' THEN 'CREDIT' ELSE 'DEBIT' END)::varchar,g.amount::numeric(24,6)
 FROM finance_payroll_gl_journal j CROSS JOIN LATERAL jsonb_array_elements(j.component_allocations) WITH ORDINALITY i(value,ordinality)
 JOIN finance_payroll_gl_component_group g ON g.organization_id=j.organization_id AND g.run_version_id=j.run_version_id
   AND g.component_kind=i.value->>'kind' AND g.source_domain=i.value->>'domain' AND g.component_code=i.value->>'code';

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
 UNION ALL
 SELECT concat_ws(':','PAYROLL_GL',j.id::text,j.entry_type,j.run_version_id::text,j.release_id::text,
    j.source_event_id::text,j.original_journal_id::text,j.debit_account_id::text,j.credit_account_id::text,
    j.amount::text,j.currency,extract(epoch FROM j.source_recorded_at)::text,
    CASE WHEN j.component_allocations<>'[]'::jsonb THEN encode(sha256(convert_to(j.component_allocations::text,'UTF8')),'hex') END)
 FROM finance_payroll_gl_journal j WHERE j.organization_id=p.organization_id AND j.period_id=p.id
 ) x
) e;


