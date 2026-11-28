-- An opening payroll payable is an existing liability, never employee funding,
-- an expense or a current payroll run. Reuse independent employee settlement
-- review, with a separate source and a real bank DEBIT, not a collection.
CREATE OR REPLACE FUNCTION finance_employee_custody_registry_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.opening_journal_id IS NOT NULL AND NOT EXISTS(
   SELECT 1 FROM finance_gl_opening_employee_obligation o WHERE o.organization_id=NEW.organization_id
     AND o.id=NEW.id AND o.opening_journal_id=NEW.opening_journal_id)
 AND NOT EXISTS(SELECT 1 FROM finance_gl_opening_payroll_obligation o WHERE o.organization_id=NEW.organization_id
   AND o.id=NEW.id AND o.opening_journal_id=NEW.opening_journal_id)
 THEN RAISE EXCEPTION 'Unknown sealed employee opening source' USING ERRCODE='23514'; END IF;
 IF NEW.payroll_recovery_journal_id IS NOT NULL AND NOT EXISTS(
   SELECT 1 FROM finance_payroll_recovery_obligation o WHERE o.organization_id=NEW.organization_id
     AND o.id=NEW.id AND o.journal_id=NEW.payroll_recovery_journal_id)
 THEN RAISE EXCEPTION 'Unknown posted payroll receivable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;

CREATE FUNCTION finance_register_payroll_opening() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO finance_employee_custody_registry(organization_id,id,opening_journal_id)
 SELECT organization_id,id,opening_journal_id FROM finance_gl_opening_payroll_obligation
 WHERE organization_id=NEW.organization_id AND opening_journal_id=NEW.id;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_register_payroll_opening AFTER INSERT ON finance_gl_opening_journal
 FOR EACH ROW EXECUTE FUNCTION finance_register_payroll_opening();
INSERT INTO finance_employee_custody_registry(organization_id,id,opening_journal_id)
 SELECT organization_id,id,opening_journal_id FROM finance_gl_opening_payroll_obligation;

CREATE OR REPLACE VIEW finance_employee_settlement_source AS
 SELECT s.organization_id,s.id,s.employee_id,s.draft_kind,s.amount::numeric AS amount,s.currency,s.bank_statement_line_id,
   s.approval_request_id,s.recorded_at,s.opening_date,s.opening_posted_at,s.opening_control_account_id,
   s.opening_fingerprint,s.document_reference,
   NULL::text AS recovery_fingerprint,NULL::uuid AS recovery_account_id,NULL::date AS recovery_date,true AS recovery_current
 FROM finance_employee_custody_source s
 UNION ALL
 SELECT o.organization_id,o.id,o.employee_id,'PAYROLL_OVERPAYMENT'::varchar(24),o.amount::numeric,o.currency,
   NULL::uuid,NULL::uuid,o.posted_at,NULL::date,NULL::timestamptz,NULL::uuid,NULL::text,NULL::text,
   encode(sha256(convert_to(jsonb_build_array(o.journal_id,o.clearance_id,o.employee_id,o.recovery_account_id,o.amount,o.currency)::text,'UTF8')),'hex'),
   o.recovery_account_id,(o.posted_at AT TIME ZONE 'UTC')::date,
   EXISTS(SELECT 1 FROM finance_payroll_gl_correction_source s WHERE s.organization_id=o.organization_id AND s.replacement_run_version_id=o.run_version_id)
 FROM finance_payroll_recovery_obligation o JOIN finance_employee_custody_registry r
  ON r.organization_id=o.organization_id AND r.id=o.id AND r.payroll_recovery_journal_id=o.journal_id
 UNION ALL
 SELECT o.organization_id,o.id,o.employee_id,'PAYROLL_OPENING_PAYABLE'::varchar(24),o.amount::numeric,o.currency,
   NULL::uuid,NULL::uuid,o.posted_at,o.opening_date,o.posted_at,o.control_account_id,o.source_fingerprint,o.document_reference,
   NULL::text,NULL::uuid,NULL::date,true
 FROM finance_gl_opening_payroll_obligation o JOIN finance_employee_custody_registry r
   ON r.organization_id=o.organization_id AND r.id=o.id AND r.opening_journal_id=o.opening_journal_id;

ALTER TABLE finance_employee_settlement_proposal DROP CONSTRAINT finance_employee_settlement_proposal_action_check,
 ADD CONSTRAINT finance_employee_settlement_proposal_action_check CHECK(action IN(
 'ADVANCE_RECOVERY_CONFIRMED','IMPREST_CASH_RETURN_CONFIRMED','IMPREST_RECEIPTS_ACCEPTED','PAYROLL_RECOVERY_CONFIRMED','PAYROLL_OPENING_PAID'));
ALTER TABLE finance_employee_settlement_record DROP CONSTRAINT finance_employee_settlement_record_action_check,
 ADD CONSTRAINT finance_employee_settlement_record_action_check CHECK(action IN(
 'ADVANCE_RECOVERY_CONFIRMED','IMPREST_CASH_RETURN_CONFIRMED','IMPREST_RECEIPTS_ACCEPTED','PAYROLL_RECOVERY_CONFIRMED','PAYROLL_OPENING_PAID'));

CREATE OR REPLACE FUNCTION finance_employee_settlement_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_employee_settlement_proposal%ROWTYPE;
        funded finance_employee_settlement_source%ROWTYPE;
        bank finance_bank_statement_intake_line%ROWTYPE;
        a approval_request%ROWTYPE;
        total_settled numeric(24,6);
        cash_used numeric(24,6);
        funding_used numeric(24,6);
        bank_settled numeric(24,6);
BEGIN
 SELECT * INTO p FROM finance_employee_settlement_proposal
 WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id;
 IF p.id IS NULL OR p.funding_record_id<>NEW.funding_record_id
   OR p.employee_id<>NEW.employee_id OR p.action<>NEW.action
   OR p.bank_statement_line_id IS DISTINCT FROM NEW.bank_statement_line_id
   OR p.settlement_evidence_id<>NEW.settlement_evidence_id
   OR p.amount<>NEW.amount OR p.currency<>NEW.currency
   OR p.prepared_by_user_id=NEW.recorded_by_user_id THEN
   RAISE EXCEPTION 'Settlement must equal exact approved immutable proposal'
     USING ERRCODE='23514';
 END IF;
 PERFORM 1 FROM finance_employee_custody_registry
 WHERE id=p.funding_record_id AND organization_id=p.organization_id FOR UPDATE;
 -- Lock the original payroll period before assessing the receivable again:
 -- a late bank return or corrected match cannot race this collection.
 PERFORM 1 FROM finance_employee_custody_registry r
   JOIN finance_payroll_gl_journal j ON j.organization_id=r.organization_id AND j.id=r.payroll_recovery_journal_id
   JOIN payroll_period per ON per.organization_id=j.organization_id AND per.id=j.payroll_period_id
   WHERE r.organization_id=p.organization_id AND r.id=p.funding_record_id FOR UPDATE OF per;
 SELECT * INTO funded FROM finance_employee_settlement_source WHERE id=p.funding_record_id AND organization_id=p.organization_id;
 IF funded.id IS NULL OR funded.employee_id<>p.employee_id OR
    funded.currency<>p.currency OR (funded.opening_posted_at IS NOT NULL AND NEW.recorded_at<funded.opening_posted_at) OR
    (funded.draft_kind='EMPLOYEE_ADVANCE'
        AND NEW.action<>'ADVANCE_RECOVERY_CONFIRMED') OR
    (funded.draft_kind='FINANCIAL_IMPREST'
        AND NEW.action NOT IN(
            'IMPREST_CASH_RETURN_CONFIRMED','IMPREST_RECEIPTS_ACCEPTED')) OR
    (funded.draft_kind='PAYROLL_OVERPAYMENT'
        AND (NEW.action<>'PAYROLL_RECOVERY_CONFIRMED' OR NOT funded.recovery_current
          OR NEW.recorded_at<funded.recorded_at)) OR
    (funded.draft_kind='PAYROLL_OPENING_PAYABLE'
        AND (NEW.action<>'PAYROLL_OPENING_PAID' OR funded.opening_fingerprint IS NULL OR funded.opening_date IS NULL)) OR
    funded.draft_kind NOT IN('EMPLOYEE_ADVANCE','FINANCIAL_IMPREST','PAYROLL_OVERPAYMENT','PAYROLL_OPENING_PAYABLE') THEN
   RAISE EXCEPTION 'Settlement source is not a funded loan or custody event'
     USING ERRCODE='23514';
 END IF;
 SELECT coalesce(sum(amount),0) INTO total_settled
 FROM finance_employee_settlement_record
 WHERE organization_id=NEW.organization_id
   AND funding_record_id=NEW.funding_record_id;
 IF total_settled+NEW.amount>funded.amount THEN
   RAISE EXCEPTION 'Cannot settle more than the recorded employee obligation'
     USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS(
     SELECT 1 FROM evidence e JOIN evidence_upload_session u
        ON u.evidence_id=e.id
     WHERE e.organization_id=p.organization_id
       AND e.id=p.settlement_evidence_id
       AND e.target_type='FINANCE_EMPLOYEE_SETTLEMENT'
       AND e.target_id=p.id AND e.sha256=p.settlement_evidence_sha256
       AND e.storage_state='READY' AND u.state='READY'
       AND e.finalized_at IS NOT NULL AND u.finalized_at IS NOT NULL
       AND e.object_key_ref IS NOT NULL
       AND e.sha256=u.expected_sha256
       AND e.size_bytes=u.expected_size_bytes
       AND e.classification_code='HIGHLY_RESTRICTED'
       AND e.client_visibility_mode='INTERNAL_ONLY') THEN
   RAISE EXCEPTION 'Unverified employee receipts or repayment evidence'
     USING ERRCODE='23514';
 END IF;
 IF NEW.action<>'IMPREST_RECEIPTS_ACCEPTED' THEN
   SELECT * INTO bank FROM finance_bank_statement_intake_line
      WHERE id=NEW.bank_statement_line_id
        AND organization_id=NEW.organization_id FOR UPDATE;
   IF bank.id IS NULL OR bank.flow<>(CASE WHEN funded.draft_kind='PAYROLL_OPENING_PAYABLE' THEN 'DEBIT' ELSE 'CREDIT' END) OR bank.currency<>NEW.currency
      OR (funded.opening_date IS NOT NULL AND bank.booked_on<funded.opening_date)
      OR (funded.recovery_date IS NOT NULL AND bank.booked_on<funded.recovery_date) THEN
     RAISE EXCEPTION 'Real separate company bank credit required for return'
       USING ERRCODE='23514';
   END IF;
   IF funded.draft_kind='PAYROLL_OPENING_PAYABLE' AND EXISTS(
     SELECT 1 FROM finance_bank_reconciliation_record rr WHERE rr.organization_id=bank.organization_id AND rr.intake_id=bank.intake_id)
   THEN RAISE EXCEPTION 'Cannot match a historical payroll payment to a signed bank statement' USING ERRCODE='23514'; END IF;
   SELECT coalesce(sum(allocated_amount),0) INTO cash_used
   FROM finance_cash_allocation_effective
   WHERE organization_id=NEW.organization_id
     AND bank_statement_line_id=NEW.bank_statement_line_id;
   SELECT coalesce(sum(amount),0) INTO funding_used
   FROM finance_employee_funding_record
   WHERE organization_id=NEW.organization_id
     AND bank_statement_line_id=NEW.bank_statement_line_id;
   SELECT coalesce(sum(amount),0) INTO bank_settled
   FROM finance_employee_settlement_record
   WHERE organization_id=NEW.organization_id
     AND bank_statement_line_id=NEW.bank_statement_line_id;
   SELECT coalesce(sum(amount),0) INTO bank_settled FROM finance_bank_committed_usage
   WHERE organization_id=NEW.organization_id AND bank_statement_line_id=NEW.bank_statement_line_id;
   IF bank_settled+NEW.amount>bank.amount THEN
     RAISE EXCEPTION 'Bank credit already allocated to another collection or return'
       USING ERRCODE='23514';
   END IF;
 END IF;
 SELECT * INTO a FROM approval_request
   WHERE id=NEW.approval_request_id AND organization_id=NEW.organization_id;
 IF a.id IS NULL OR a.state<>'APPROVED'
    OR a.subject_type<>'FINANCE_EMPLOYEE_SETTLEMENT'
    OR a.subject_id IS DISTINCT FROM p.id OR a.subject_version<>p.review_version
    OR a.policy_key<>p.policy_key
    OR a.requester_user_id<>p.prepared_by_user_id
    OR a.requester_user_id=NEW.recorded_by_user_id
    OR a.completed_at IS NULL OR a.completed_at>NEW.recorded_at
    OR NOT EXISTS(
       SELECT 1 FROM approval_decision d
       WHERE d.approval_request_id=a.id AND d.decision='APPROVE'
         AND d.subject_version=a.subject_version
         AND d.actor_user_id<>p.prepared_by_user_id
         AND d.actor_user_id<>NEW.recorded_by_user_id)
    OR NOT EXISTS(
       SELECT 1 FROM approval_policy_version v
       WHERE v.organization_id=NEW.organization_id
         AND v.policy_key=p.policy_key
         AND v.version_number=a.policy_version
         AND v.lifecycle_state='ACTIVE' AND v.approval_required=true
         AND v.step_mode='SINGLE'
         AND v.effective_from<=NEW.recorded_at
         AND (v.effective_to IS NULL OR v.effective_to>NEW.recorded_at)) THEN
   RAISE EXCEPTION 'Independent approved employee repayment or custody settlement required'
     USING ERRCODE='23514';
 END IF;
 IF funded.draft_kind IN('PAYROLL_OVERPAYMENT','PAYROLL_OPENING_PAYABLE') AND EXISTS(
   SELECT 1 FROM employee emp JOIN user_identity u ON u.person_id=emp.person_id
   WHERE emp.organization_id=NEW.organization_id AND emp.id=NEW.employee_id
   AND (u.id IN(p.prepared_by_user_id,NEW.recorded_by_user_id) OR EXISTS(
     SELECT 1 FROM approval_decision d WHERE d.approval_request_id=a.id AND d.actor_user_id=u.id AND d.decision='APPROVE')))
 THEN RAISE EXCEPTION 'Employee cannot authorize their own payroll settlement' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;

ALTER TABLE finance_settlement_gl_journal
 DROP CONSTRAINT finance_settlement_gl_journal_source_type_check,
 DROP CONSTRAINT finance_settlement_gl_journal_source_kind_check,
 DROP CONSTRAINT finance_settlement_gl_source_scope_check,
 ADD CONSTRAINT finance_settlement_gl_journal_source_type_check CHECK(source_type IN(
 'BANK_ALLOCATION','CASH_VOUCHER','BANK_VOID','EMPLOYEE_FUNDING','EMPLOYEE_RETURN','IMPREST_EXPENSE','EXPENSE_REIMBURSEMENT','PAYROLL_RECOVERY','PAYROLL_OPENING_PAYMENT')),
 ADD CONSTRAINT finance_settlement_gl_journal_source_kind_check CHECK(source_kind IN('AP','AR','EA','EI','ER','PR','PO')),
 ADD CONSTRAINT finance_settlement_gl_source_scope_check CHECK(
    (source_type IN('BANK_ALLOCATION','CASH_VOUCHER','BANK_VOID') AND source_kind IN('AP','AR')
       AND source_journal_id IS NOT NULL AND employee_funding_record_id IS NULL AND expense_claim_id IS NULL)
 OR (source_type IN('EMPLOYEE_FUNDING','EMPLOYEE_RETURN','IMPREST_EXPENSE') AND source_kind IN('EA','EI')
       AND source_journal_id IS NULL AND employee_funding_record_id IS NOT NULL AND expense_claim_id IS NULL)
 OR (source_type='EXPENSE_REIMBURSEMENT' AND source_kind='ER'
       AND source_journal_id IS NULL AND employee_funding_record_id IS NULL AND expense_claim_id IS NOT NULL)
 OR (((source_type='PAYROLL_RECOVERY' AND source_kind='PR') OR (source_type='PAYROLL_OPENING_PAYMENT' AND source_kind='PO'))
       AND source_journal_id IS NULL AND employee_funding_record_id IS NOT NULL AND expense_claim_id IS NULL));

CREATE OR REPLACE VIEW finance_settlement_gl_source_v2 AS
WITH funding AS (
 SELECT f.*,CASE WHEN f.draft_kind='EMPLOYEE_ADVANCE' THEN 'EA' ELSE 'EI' END AS custody_kind,
        CASE WHEN f.draft_kind='EMPLOYEE_ADVANCE' THEN 'EMPLOYEE_ADVANCE_ASSET' ELSE 'EMPLOYEE_IMPREST_ASSET' END AS custody_account_kind,
        p.display_name AS employee_name
 FROM finance_employee_custody_source f
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
 r.employee_id,f.employee_name,NULL::uuid,g.id,coalesce(f.opening_control_account_id,j.debit_account_id),f.id,NULL::uuid,
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
 AND g.currency=r.currency AND g.state='APPROVED'
UNION ALL
SELECT r.organization_id,'PAYROLL_RECOVERY',r.id,'PR',NULL::uuid,r.approval_request_id,
 r.amount,r.currency,r.recorded_at,'BANK',b.account_id,a.bank_name || ' · ' || a.masked_account_reference,
 r.employee_id,p.display_name,NULL::uuid,g.id,o.recovery_account_id,o.id,NULL::uuid,NULL::varchar
FROM finance_employee_settlement_record r
JOIN finance_payroll_recovery_obligation o ON o.organization_id=r.organization_id AND o.id=r.funding_record_id
JOIN employee e ON e.organization_id=r.organization_id AND e.id=r.employee_id JOIN person p ON p.id=e.person_id
JOIN finance_bank_statement_intake_line b ON b.organization_id=r.organization_id AND b.id=r.bank_statement_line_id
JOIN finance_bank_account_register a ON a.organization_id=b.organization_id AND a.id=b.account_id
LEFT JOIN finance_gl_account g ON g.organization_id=b.organization_id AND g.treasury_bank_account_id=b.account_id
 AND g.currency=r.currency AND g.state='APPROVED'
WHERE r.action='PAYROLL_RECOVERY_CONFIRMED'
UNION ALL
SELECT r.organization_id,'PAYROLL_OPENING_PAYMENT',r.id,'PO',NULL::uuid,r.approval_request_id,
 r.amount,r.currency,r.recorded_at,'BANK',b.account_id,a.bank_name || ' · ' || a.masked_account_reference,
 r.employee_id,p.display_name,NULL::uuid,o.control_account_id,g.id,o.id,NULL::uuid,NULL::varchar
FROM finance_employee_settlement_record r
JOIN finance_gl_opening_payroll_obligation o ON o.organization_id=r.organization_id AND o.id=r.funding_record_id
JOIN employee e ON e.organization_id=r.organization_id AND e.id=r.employee_id JOIN person p ON p.id=e.person_id
JOIN finance_bank_statement_intake_line b ON b.organization_id=r.organization_id AND b.id=r.bank_statement_line_id
JOIN finance_bank_account_register a ON a.organization_id=b.organization_id AND a.id=b.account_id
LEFT JOIN finance_gl_account g ON g.organization_id=b.organization_id AND g.treasury_bank_account_id=b.account_id
 AND g.currency=r.currency AND g.state='APPROVED'
WHERE r.action='PAYROLL_OPENING_PAID';

CREATE VIEW finance_payroll_opening_balance AS
 SELECT o.*,o.amount-coalesce(r.recorded,0) AS remaining_to_record,
   o.amount-coalesce(r.posted,0) AS remaining_to_post
 FROM finance_gl_opening_payroll_obligation o LEFT JOIN LATERAL (
   SELECT sum(r.amount) AS recorded,sum(r.amount) FILTER(WHERE j.id IS NOT NULL) AS posted
   FROM finance_employee_settlement_record r LEFT JOIN finance_settlement_gl_journal j
     ON j.organization_id=r.organization_id AND j.source_type='PAYROLL_OPENING_PAYMENT' AND j.source_id=r.id
   WHERE r.organization_id=o.organization_id AND r.funding_record_id=o.id AND r.action='PAYROLL_OPENING_PAID'
 ) r ON true;
