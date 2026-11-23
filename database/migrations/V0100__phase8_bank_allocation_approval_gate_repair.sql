-- Phase 8 original AP/AR positive-posting repair; forward-only Flyway migration.
-- Reuse all existing source journal locks, bank capacity, source caps and
-- approval requirements from V0090 unchanged. Never infer or bypass authority.
-- The original PL/pgSQL row variable plus nested SELECT name resolution
-- rejected a source-backed independently approved collection in PG 18.
-- Re-evaluate the *same* full approval invariants through one correlated
-- SQL EXISTS query with explicit SQL aliases.
CREATE OR REPLACE FUNCTION finance_cash_allocation_record_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_cash_allocation_proposal%ROWTYPE;
        bank finance_bank_statement_intake_line%ROWTYPE;
        source_amount numeric(24,6);
        source_currency char(3);
        source_hash char(64);
        old_source_total numeric(24,6);
        old_bank_total numeric(24,6);
        old_employee_funding numeric(24,6);
        source_matched uuid;
        evidence finance_bank_statement_intake%ROWTYPE;
BEGIN
 SELECT * INTO p FROM finance_cash_allocation_proposal
   WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id;
 IF p.id IS NULL OR p.source_kind<>NEW.source_kind
    OR p.source_journal_id<>NEW.source_journal_id
    OR p.bank_statement_line_id<>NEW.bank_statement_line_id
    OR p.currency<>NEW.currency OR p.allocated_amount<>NEW.allocated_amount
    OR p.approval_subject_version<>NEW.approval_subject_version
    OR p.policy_key<>NEW.policy_key
    OR p.prepared_by_user_id=NEW.recorded_by_user_id THEN
  RAISE EXCEPTION 'Cash allocation must exactly match prepared immutable proposal'
   USING ERRCODE='23514';
 END IF;
 IF NEW.source_kind='AR' THEN
   SELECT j.id,j.gross_amount,j.currency,j.ledger_fingerprint_sha256
   INTO source_matched,source_amount,source_currency,source_hash
   FROM finance_ar_posted_journal j
   WHERE j.id=NEW.source_journal_id AND j.organization_id=NEW.organization_id
   FOR UPDATE;
 ELSE
   SELECT j.id,j.gross_amount,j.currency,j.ledger_fingerprint_sha256
   INTO source_matched,source_amount,source_currency,source_hash
   FROM finance_ap_posted_journal j
   WHERE j.id=NEW.source_journal_id AND j.organization_id=NEW.organization_id
   FOR UPDATE;
 END IF;
 IF NEW.source_kind='AP' AND EXISTS(
   SELECT 1 FROM finance_ap_journal_reversal r
   WHERE r.organization_id=NEW.organization_id
     AND r.original_journal_id=NEW.source_journal_id
 ) THEN
   RAISE EXCEPTION 'Reversed AP cannot be linked to new bank payment'
     USING ERRCODE='23514';
 END IF;
 IF source_matched IS NULL OR source_currency<>NEW.currency
    OR source_hash<>p.source_journal_sha256 THEN
  RAISE EXCEPTION 'Original posted accounting journal required'
    USING ERRCODE='23514';
 END IF;
 SELECT * INTO bank FROM finance_bank_statement_intake_line
  WHERE id=NEW.bank_statement_line_id AND organization_id=NEW.organization_id
  FOR UPDATE;
 SELECT * INTO evidence FROM finance_bank_statement_intake
  WHERE id=bank.intake_id AND organization_id=NEW.organization_id;
 IF bank.id IS NULL OR evidence.id IS NULL
    OR evidence.statement_sha256<>p.bank_statement_sha256
    OR bank.currency<>NEW.currency OR
    (NEW.source_kind='AR' AND bank.flow<>'CREDIT') OR
    (NEW.source_kind='AP' AND bank.flow<>'DEBIT') THEN
   RAISE EXCEPTION 'Verified company bank credit/debit required'
     USING ERRCODE='23514';
 END IF;
 SELECT coalesce(sum(allocated_amount),0) INTO old_source_total
 FROM finance_cash_allocation_effective
 WHERE organization_id=NEW.organization_id AND source_kind=NEW.source_kind
   AND source_journal_id=NEW.source_journal_id;
 SELECT coalesce(sum(allocated_amount),0) INTO old_bank_total
 FROM finance_cash_allocation_effective
 WHERE organization_id=NEW.organization_id
   AND bank_statement_line_id=NEW.bank_statement_line_id;
 SELECT coalesce(sum(amount),0) INTO old_employee_funding
 FROM finance_employee_funding_record
 WHERE organization_id=NEW.organization_id
   AND bank_statement_line_id=NEW.bank_statement_line_id;
 IF old_source_total+NEW.allocated_amount>source_amount
    OR old_bank_total+old_employee_funding+NEW.allocated_amount>bank.amount THEN
  RAISE EXCEPTION 'Source, bank or employee funding cannot be over-allocated'
    USING ERRCODE='23514';
 END IF;
 SELECT * INTO a FROM approval_reque -- All policy, separation and decision checks must belong to the
 -- same exact approved request. Avoid PL/pgSQL record/SQL alias ambiguity
 -- in nested EXISTS during the actual BEFORE INSERT trigger.
 IF NOT EXISTS (
   SELECT 1 FROM approval_request approved_request
   WHERE approved_request.id=NEW.approval_request_id
     AND approved_request.organization_id=NEW.organization_id
     AND approved_request.state='APPROVED'
     AND approved_request.subject_type='FINANCE_AP_AR_CASH_ALLOCATION'
     AND approved_request.subject_id=p.id
     AND approved_request.subject_version=p.approval_subject_version
     AND approved_request.policy_key=p.policy_key
     AND approved_request.requester_user_id=p.prepared_by_user_id
     AND approved_request.requester_user_id<>NEW.recorded_by_user_id
     AND approved_request.completed_at IS NOT NULL
     AND approved_request.completed_at<=NEW.recorded_at
     AND EXISTS (
       SELECT 1 FROM approval_decision decision_row
       WHERE decision_row.approval_request_id=approved_request.id
         AND decision_row.decision='APPROVE'
         AND decision_row.subject_version=approved_request.subject_version
         AND decision_row.actor_user_id<>approved_request.requester_user_id
         AND decision_row.actor_user_id<>NEW.recorded_by_user_id
     )
     AND EXISTS (
       SELECT 1 FROM approval_policy_version active_policy
       WHERE active_policy.organization_id=NEW.organization_id
         AND active_policy.policy_key=approved_request.policy_key
         AND active_policy.version_number=approved_request.policy_version
         AND active_policy.lifecycle_state='ACTIVE'
         AND active_policy.approval_required=true
         AND active_policy.step_mode='SINGLE'
         AND active_policy.effective_from<=NEW.recorded_at
         AND (active_policy.effective_to IS NULL
              OR active_policy.effective_to>NEW.recorded_at)
     )
 ) THEN
   RAISE EXCEPTION 'Independent original-bound bank allocation approval required'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
