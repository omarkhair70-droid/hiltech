-- Preserve the immutable migration history. The original guard rejected the exact
-- approved subject and accepted a mismatched subject when other checks matched.
CREATE OR REPLACE FUNCTION finance_employee_settlement_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_employee_settlement_proposal%ROWTYPE;
        funded finance_employee_funding_record%ROWTYPE;
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
 SELECT * INTO funded FROM finance_employee_funding_record
 WHERE id=p.funding_record_id AND organization_id=p.organization_id FOR UPDATE;
 IF funded.id IS NULL OR funded.employee_id<>p.employee_id OR
    funded.currency<>p.currency OR
    (funded.draft_kind='EMPLOYEE_ADVANCE'
        AND NEW.action<>'ADVANCE_RECOVERY_CONFIRMED') OR
    (funded.draft_kind='FINANCIAL_IMPREST'
        AND NEW.action NOT IN(
            'IMPREST_CASH_RETURN_CONFIRMED','IMPREST_RECEIPTS_ACCEPTED')) THEN
   RAISE EXCEPTION 'Settlement source is not a funded loan or custody event'
     USING ERRCODE='23514';
 END IF;
 SELECT coalesce(sum(amount),0) INTO total_settled
 FROM finance_employee_settlement_record
 WHERE organization_id=NEW.organization_id
   AND funding_record_id=NEW.funding_record_id;
 IF total_settled+NEW.amount>funded.amount THEN
   RAISE EXCEPTION 'Cannot settle more than company actually funded'
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
   IF bank.id IS NULL OR bank.flow<>'CREDIT' OR bank.currency<>NEW.currency THEN
     RAISE EXCEPTION 'Real separate company bank credit required for return'
       USING ERRCODE='23514';
   END IF;
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
   IF bank_settled+cash_used+funding_used+NEW.amount>bank.amount THEN
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
 RETURN NEW;
END; $$;
