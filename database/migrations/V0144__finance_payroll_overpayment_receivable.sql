-- Recognize exact overpayments as employee receivables, not new advances or cash.
-- The existing payroll correction clearance remains the authority. An approved
-- OTHER_ASSET account is explicitly selected by Finance, never inferred.
-- This migration does not authorize collection or deduction from future wages.
ALTER TABLE finance_payroll_correction_clearance
 ADD COLUMN recovery_account_id uuid,
 ADD COLUMN recovery_schedule jsonb NOT NULL DEFAULT '[]'::jsonb,
 ADD CONSTRAINT finance_payroll_clearance_recovery_account_fk FOREIGN KEY(organization_id,recovery_account_id)
   REFERENCES finance_gl_account(organization_id,id) ON DELETE RESTRICT,
 ADD CONSTRAINT finance_payroll_clearance_recovery_schedule_check CHECK(
   jsonb_typeof(recovery_schedule)='array' AND jsonb_array_length(recovery_schedule)<=10000
   AND ((recovery_account_id IS NULL)=(recovery_schedule='[]'::jsonb))
   AND (recovery_account_id IS NULL OR disposition='SETTLED_DIFFERENTIAL_AUTHORIZED'));

CREATE OR REPLACE FUNCTION finance_payroll_settled_delta_fingerprint(org uuid,replacement uuid)
 RETURNS text LANGUAGE sql STABLE AS $$
 SELECT encode(sha256(convert_to(jsonb_build_object(
   'schema','SETTLED_PAYROLL_POSITIVE_V1','plan',to_jsonb(p),
   'employees',(SELECT jsonb_agg(to_jsonb(b) ORDER BY b.employee_id)
     FROM finance_payroll_settled_delta_basis b
     WHERE b.organization_id=org AND b.replacement_run_version_id=replacement),
   'priorEvidence',(SELECT jsonb_agg(jsonb_build_object('instruction',i.id,'event',e.id,'version',e.version,
       'action',e.action,'outcome',e.outcome,'amount',e.amount,'currency',e.currency,
       'bankLine',e.bank_statement_line_id,'evidence',e.evidence_id,'evidenceSha256',e.evidence_sha256,
       'corrects',e.corrects_event_id,'journal',j.id) ORDER BY i.id,e.id)
     FROM finance_payroll_instruction i
     JOIN payroll_run_version r ON r.organization_id=i.organization_id AND r.id=i.run_version_id
     JOIN payroll_run_version newer ON newer.organization_id=r.organization_id AND newer.id=replacement
     JOIN finance_payroll_result_event e ON e.organization_id=i.organization_id AND e.instruction_id=i.id
     JOIN finance_payroll_gl_journal j ON j.organization_id=e.organization_id AND j.source_event_id=e.id
     WHERE i.organization_id=org AND r.payroll_period_id=newer.payroll_period_id AND r.generation<newer.generation)
 )::text,'UTF8')),'hex')
 FROM finance_payroll_settled_delta_plan p
 WHERE p.organization_id=org AND p.replacement_run_version_id=replacement

   AND ((p.plan_state='REVIEW_READY' AND p.additional_due>0 AND p.recovery_due=0)
     OR (p.plan_state='RECOVERY_ACCOUNT_REQUIRED' AND p.recovery_due>0
       -- For now require the same frozen employee cohort. A removed employee
       -- needs a separate former-employee recovery review.
       AND NOT EXISTS(SELECT 1 FROM finance_payroll_settled_delta_basis b
         WHERE b.organization_id=org AND b.replacement_run_version_id=replacement
           AND (NOT EXISTS(SELECT 1 FROM payroll_run_employee_line l
                  WHERE l.run_version_id=b.original_run_version_id AND l.employee_id=b.employee_id)
             OR NOT EXISTS(SELECT 1 FROM payroll_run_employee_line l
                  WHERE l.run_version_id=b.replacement_run_version_id AND l.employee_id=b.employee_id)))))
$$;

CREATE OR REPLACE FUNCTION finance_payroll_correction_clearance_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c record; prior bigint; recovery numeric; schedule jsonb;
BEGIN
 PERFORM 1 FROM payroll_period p JOIN payroll_run_version r ON r.payroll_period_id=p.id
 WHERE r.organization_id=NEW.organization_id AND r.id=NEW.replacement_run_version_id FOR UPDATE OF p;
 SELECT * INTO c FROM finance_payroll_correction_candidate v WHERE v.organization_id=NEW.organization_id
    AND v.replacement_run_version_id=NEW.replacement_run_version_id;
 SELECT coalesce(max(revision),0) INTO prior FROM finance_payroll_correction_clearance r
 WHERE r.organization_id=NEW.organization_id AND r.replacement_run_version_id=NEW.replacement_run_version_id;
 IF c.replacement_run_version_id IS NULL OR c.original_run_version_id<>NEW.original_run_version_id
   OR c.original_fingerprint<>NEW.original_fingerprint OR c.replacement_fingerprint<>NEW.replacement_fingerprint
   OR c.eligible_disposition IS DISTINCT FROM NEW.disposition OR NEW.revision<>prior+1
   OR (NEW.disposition='SETTLED_DIFFERENTIAL_AUTHORIZED' AND
       (NEW.settlement_fingerprint IS NULL OR NEW.settlement_fingerprint IS DISTINCT FROM finance_payroll_settled_delta_fingerprint(NEW.organization_id,NEW.replacement_run_version_id)))
   OR c.draft_creator=NEW.reviewed_by_user_id
   OR NOT finance_payroll_current_authority(NEW.organization_id,NEW.reviewed_by_user_id,'REVIEW')
 THEN RAISE EXCEPTION 'Exact current correction and independent finance clearance required' USING ERRCODE='23514'; END IF;
 -- No client-supplied debt schedule. Freeze exact employee amounts once,
 -- from the reviewed settlement source while holding the payroll-period lock.
 IF NEW.recovery_schedule<>'[]'::jsonb THEN
   RAISE EXCEPTION 'Payroll recovery schedule is source derived' USING ERRCODE='23514';
 END IF;
 IF NEW.disposition='SETTLED_DIFFERENTIAL_AUTHORIZED' THEN
   SELECT p.recovery_due INTO recovery FROM finance_payroll_settled_delta_plan p
    WHERE p.organization_id=NEW.organization_id AND p.replacement_run_version_id=NEW.replacement_run_version_id;
   IF recovery IS NULL OR (recovery>0)<>(NEW.recovery_account_id IS NOT NULL)
   THEN RAISE EXCEPTION 'Exact approved payroll recovery account required' USING ERRCODE='23514'; END IF;
   IF recovery>0 THEN
     IF NOT EXISTS(SELECT 1 FROM finance_gl_account g WHERE g.organization_id=NEW.organization_id
       AND g.id=NEW.recovery_account_id AND g.account_kind='OTHER_ASSET' AND g.state='APPROVED'
       AND (g.currency IS NULL OR g.currency=c.currency))
     OR EXISTS(SELECT 1 FROM finance_payroll_settled_delta_basis b
       JOIN employee e ON e.organization_id=b.organization_id AND e.id=b.employee_id
       JOIN user_identity u ON u.person_id=e.person_id
       WHERE b.organization_id=NEW.organization_id AND b.replacement_run_version_id=NEW.replacement_run_version_id
         AND b.recovery_due>0 AND u.id=NEW.reviewed_by_user_id)
     THEN RAISE EXCEPTION 'Independent reviewer and approved employee recovery asset account required' USING ERRCODE='23514'; END IF;
     SELECT jsonb_agg(jsonb_build_object('employeeId',b.employee_id,'amount',b.recovery_due) ORDER BY b.employee_id)
       INTO schedule FROM finance_payroll_settled_delta_basis b WHERE b.organization_id=NEW.organization_id
         AND b.replacement_run_version_id=NEW.replacement_run_version_id AND b.recovery_due>0;
     IF schedule IS NULL OR (SELECT sum((x->>'amount')::numeric) FROM jsonb_array_elements(schedule) x)<>recovery
     THEN RAISE EXCEPTION 'Payroll recovery schedule must balance' USING ERRCODE='23514'; END IF;
     NEW.recovery_schedule:=schedule;
   END IF;
 ELSIF NEW.recovery_account_id IS NOT NULL THEN
   RAISE EXCEPTION 'Recovery account is not applicable to this clearance' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $;

-- The exact clearance cited by the immutable correction, independent of
-- later source re-evaluation. This is deliberately not a cash receipt.
CREATE VIEW finance_payroll_recovery_source AS
 SELECT c.organization_id,c.replacement_run_version_id AS run_version_id,r.id AS clearance_id,
   r.recovery_account_id,r.recovery_schedule,v.currency,
   (SELECT sum((x->>'amount')::numeric)::numeric(24,6) FROM jsonb_array_elements(r.recovery_schedule) x) AS amount
 FROM payroll_run_correction c
 JOIN payroll_run_release p ON p.organization_id=c.organization_id AND p.run_version_id=c.replacement_run_version_id
 JOIN payroll_run_version v ON v.organization_id=c.organization_id AND v.id=c.replacement_run_version_id
 JOIN finance_payroll_correction_clearance r ON r.organization_id=c.organization_id
   AND r.replacement_run_version_id=c.replacement_run_version_id AND r.original_run_version_id=c.original_run_version_id
   AND c.finance_clearance_ref='FINANCE_CLEARANCE:'||r.id::text AND c.finance_clearance_version=r.revision
   AND r.original_fingerprint=c.original_source_fingerprint AND r.replacement_fingerprint=c.replacement_source_fingerprint
 WHERE c.finance_disposition='SETTLED_DIFFERENTIAL_AUTHORIZED' AND r.recovery_account_id IS NOT NULL;

-- A second correction of a payroll with a frozen recovery schedule requires
-- the future recovery reconciliation/reversal path. Do not erase that debt.
CREATE FUNCTION finance_payroll_recovery_chain_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM payroll_period p JOIN payroll_run_version r ON r.payroll_period_id=p.id
   WHERE r.organization_id=NEW.organization_id AND r.id=NEW.replacement_run_version_id FOR UPDATE OF p;
 IF NEW.finance_disposition='SETTLED_DIFFERENTIAL_AUTHORIZED' AND EXISTS(
   SELECT 1 FROM finance_payroll_correction_clearance c
   JOIN payroll_run_version v ON v.organization_id=c.organization_id AND v.id=c.replacement_run_version_id
   WHERE c.organization_id=NEW.organization_id AND c.replacement_run_version_id=NEW.replacement_run_version_id
     AND NEW.finance_clearance_ref='FINANCE_CLEARANCE:'||c.id::text AND NEW.finance_clearance_version=c.revision
     AND c.recovery_account_id IS NOT NULL AND NOT EXISTS(
       SELECT 1 FROM finance_gl_account a WHERE a.organization_id=c.organization_id AND a.id=c.recovery_account_id
         AND a.account_kind='OTHER_ASSET' AND a.state='APPROVED' AND (a.currency IS NULL OR a.currency=v.currency)))
 THEN RAISE EXCEPTION 'Payroll recovery account approval changed' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM finance_payroll_recovery_source s
   JOIN payroll_run_version prior_run ON prior_run.organization_id=s.organization_id AND prior_run.id=s.run_version_id
   JOIN payroll_run_version next_run ON next_run.organization_id=prior_run.organization_id AND next_run.id=NEW.replacement_run_version_id
   WHERE s.organization_id=NEW.organization_id AND prior_run.payroll_period_id=next_run.payroll_period_id AND prior_run.generation<next_run.generation)
 THEN RAISE EXCEPTION 'Existing employee recovery must be resolved before another payroll correction' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_payroll_recovery_chain_guard BEFORE INSERT ON payroll_run_correction
 FOR EACH ROW EXECUTE FUNCTION finance_payroll_recovery_chain_guard();

-- Guard account approval again at posting, under the existing payroll and
-- fiscal-period locks. Posting lines are derived from the immutable clearance.
CREATE FUNCTION finance_payroll_recovery_gl_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s finance_payroll_recovery_source%ROWTYPE; expected numeric;
BEGIN
 IF NEW.entry_type<>'PAYROLL_ACCRUAL' THEN RETURN NEW; END IF;
 SELECT * INTO s FROM finance_payroll_recovery_source WHERE organization_id=NEW.organization_id AND run_version_id=NEW.run_version_id;
 IF NOT FOUND THEN RETURN NEW; END IF;
 SELECT recovery_due INTO expected FROM finance_payroll_settled_delta_plan
   WHERE organization_id=NEW.organization_id AND replacement_run_version_id=NEW.run_version_id;
 IF expected IS DISTINCT FROM s.amount OR s.amount<=0 OR NOT EXISTS(
   SELECT 1 FROM finance_gl_account a WHERE a.organization_id=NEW.organization_id AND a.id=s.recovery_account_id
    AND a.account_kind='OTHER_ASSET' AND a.state='APPROVED' AND (a.currency IS NULL OR a.currency=NEW.currency))
 THEN RAISE EXCEPTION 'Exact current approved recovery asset and schedule required for payroll posting' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER zz_finance_payroll_recovery_gl_guard BEFORE INSERT ON finance_payroll_gl_journal
 FOR EACH ROW EXECUTE FUNCTION finance_payroll_recovery_gl_guard();

CREATE OR REPLACE VIEW finance_payroll_instruction_entitlement AS
 SELECT r.organization_id,r.id AS run_version_id,l.employee_id,l.currency,l.net AS amount
 FROM payroll_run_version r JOIN payroll_run_employee_line l ON l.run_version_id=r.id
 WHERE NOT EXISTS(SELECT 1 FROM payroll_run_correction c WHERE c.organization_id=r.organization_id
     AND c.replacement_run_version_id=r.id AND c.finance_disposition='SETTLED_DIFFERENTIAL_AUTHORIZED')
 UNION ALL
 SELECT b.organization_id,b.replacement_run_version_id,b.employee_id,b.currency,b.additional_due AS amount
 FROM finance_payroll_settled_delta_basis b JOIN finance_payroll_gl_correction_source c
   ON c.organization_id=b.organization_id AND c.replacement_run_version_id=b.replacement_run_version_id
   AND c.finance_disposition='SETTLED_DIFFERENTIAL_AUTHORIZED'
 WHERE b.all_employees_ready AND b.additional_due IS NOT NULL;

CREATE OR REPLACE VIEW finance_payroll_gl_journal_line AS
 SELECT organization_id,journal_id,line_number,gl_account_id,side,amount FROM finance_payroll_gl_base_line
 UNION ALL
 SELECT j.organization_id,j.id,32+l.line_number,l.gl_account_id,
   (CASE WHEN l.side='DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END)::varchar,l.amount
 FROM finance_payroll_gl_journal j JOIN finance_payroll_gl_base_line l
   ON l.organization_id=j.organization_id AND l.journal_id=j.original_journal_id
 WHERE j.entry_type='PAYROLL_ACCRUAL'
 UNION ALL
 SELECT j.organization_id,j.id,65,s.recovery_account_id,'DEBIT'::varchar,s.amount
 FROM finance_payroll_gl_journal j JOIN finance_payroll_recovery_source s
   ON s.organization_id=j.organization_id AND s.run_version_id=j.run_version_id
 WHERE j.entry_type='PAYROLL_ACCRUAL'
 UNION ALL
 SELECT j.organization_id,j.id,66,j.credit_account_id,'CREDIT'::varchar,s.amount
 FROM finance_payroll_gl_journal j JOIN finance_payroll_recovery_source s
   ON s.organization_id=j.organization_id AND s.run_version_id=j.run_version_id
 WHERE j.entry_type='PAYROLL_ACCRUAL';

-- Posted per-employee receivables can be used by a subsequent evidence-backed
-- settlement flow. Never create a second employee funding record.
CREATE VIEW finance_payroll_recovery_obligation AS
 SELECT s.organization_id,md5(s.clearance_id::text||':payroll-recovery:'||(x->>'employeeId'))::uuid AS id,
   s.run_version_id,j.id AS journal_id,s.clearance_id,s.recovery_account_id,
   (x->>'employeeId')::uuid AS employee_id,s.currency,(x->>'amount')::numeric(24,6) AS amount,j.posted_at
 FROM finance_payroll_recovery_source s JOIN finance_payroll_gl_journal j
   ON j.organization_id=s.organization_id AND j.run_version_id=s.run_version_id AND j.entry_type='PAYROLL_ACCRUAL'
 CROSS JOIN LATERAL jsonb_array_elements(s.recovery_schedule) x;

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
    CASE WHEN j.component_allocations<>'[]'::jsonb THEN encode(sha256(convert_to(j.component_allocations::text,'UTF8')),'hex') END,
    (SELECT encode(sha256(convert_to(jsonb_build_array(s.recovery_account_id,s.recovery_schedule)::text,'UTF8')),'hex')
       FROM finance_payroll_recovery_source s WHERE s.organization_id=j.organization_id AND s.run_version_id=j.run_version_id
         AND j.entry_type='PAYROLL_ACCRUAL'))
 FROM finance_payroll_gl_journal j WHERE j.organization_id=p.organization_id AND j.period_id=p.id
 UNION ALL
 SELECT concat_ws(':','OPENING_BALANCE',j.id::text,j.currency,j.opening_date::text,
    encode(sha256(convert_to(jsonb_build_array(j.company_reference,j.source_reference,j.lines)::text,'UTF8')),'hex'))
 FROM finance_gl_opening_journal j WHERE j.organization_id=p.organization_id AND j.period_id=p.id
 ) x
) e;
