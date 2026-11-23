-- Phase 8 employee advance / company imprest settlement. UNNUMBERED / OFF.
-- Not reimbursement of personal expenses; those have a distinct accounting
-- approval and must never use company advance/custody ledgers.
CREATE TABLE finance_employee_settlement_proposal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 funding_record_id uuid NOT NULL,
 employee_id uuid NOT NULL REFERENCES employee(id) ON DELETE RESTRICT,
 action varchar(32) NOT NULL CHECK(action IN(
   'ADVANCE_RECOVERY_CONFIRMED',
   'IMPREST_CASH_RETURN_CONFIRMED','IMPREST_RECEIPTS_ACCEPTED')),
 bank_statement_line_id uuid,
 settlement_evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
 settlement_evidence_sha256 char(64) NOT NULL
   CHECK(settlement_evidence_sha256 ~ '^[a-f0-9]{64}$'),
 amount numeric(18,6) NOT NULL CHECK(amount>0),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 review_version bigint NOT NULL CHECK(review_version>0),
 policy_key varchar(80) NOT NULL CHECK(policy_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
 prepared_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 UNIQUE(organization_id,id),
 FOREIGN KEY(funding_record_id,organization_id)
  REFERENCES finance_employee_funding_record(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(bank_statement_line_id,organization_id)
  REFERENCES finance_bank_statement_intake_line(id,organization_id) ON DELETE RESTRICT,
 CHECK((action='IMPREST_RECEIPTS_ACCEPTED' AND bank_statement_line_id IS NULL)
       OR(action<>'IMPREST_RECEIPTS_ACCEPTED' AND bank_statement_line_id IS NOT NULL))
);
CREATE TRIGGER finance_employee_settlement_proposal_immutable
BEFORE UPDATE OR DELETE ON finance_employee_settlement_proposal FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE TABLE finance_employee_settlement_record (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 proposal_id uuid NOT NULL,
 funding_record_id uuid NOT NULL,
 employee_id uuid NOT NULL REFERENCES employee(id) ON DELETE RESTRICT,
 action varchar(32) NOT NULL CHECK(action IN(
   'ADVANCE_RECOVERY_CONFIRMED',
   'IMPREST_CASH_RETURN_CONFIRMED','IMPREST_RECEIPTS_ACCEPTED')),
 bank_statement_line_id uuid,
 settlement_evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
 approval_request_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
 amount numeric(18,6) NOT NULL CHECK(amount>0),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 recorded_at timestamptz NOT NULL,
 correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,proposal_id),
 FOREIGN KEY(proposal_id,organization_id)
  REFERENCES finance_employee_settlement_proposal(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(funding_record_id,organization_id)
  REFERENCES finance_employee_funding_record(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(bank_statement_line_id,organization_id)
  REFERENCES finance_bank_statement_intake_line(id,organization_id) ON DELETE RESTRICT
);
CREATE FUNCTION finance_employee_settlement_guard()
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
   FROM finance_cash_allocation_record
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
    OR a.subject_id=p.id OR a.subject_version<>p.review_version
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
CREATE TRIGGER finance_employee_settlement_guard
BEFORE INSERT ON finance_employee_settlement_record FOR EACH ROW
EXECUTE FUNCTION finance_employee_settlement_guard();
CREATE TRIGGER finance_employee_settlement_immutable
BEFORE UPDATE OR DELETE ON finance_employee_settlement_record FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

-- Final cross-ledger protection when a bank line has AP/AR collection,
-- employee funding and employee advance/imprest settlement allocations.
-- Both posting triggers lock their bank line, making aggregate checks safe
-- against simultaneous double-posts in SERIALIZABLE/READ COMMITTED.
CREATE FUNCTION finance_bank_shared_allocation_cap_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE bank_id uuid;
        org uuid;
        capacity numeric(24,6);
        used_cash numeric(24,6);
        used_funding numeric(24,6);
        used_settlement numeric(24,6);
BEGIN
 org:=NEW.organization_id;
 IF TG_TABLE_NAME='finance_cash_allocation_record' THEN
    bank_id:=NEW.bank_statement_line_id;
 ELSIF TG_TABLE_NAME='finance_employee_funding_record' THEN
    bank_id:=NEW.bank_statement_line_id;
 ELSE
    bank_id:=NEW.bank_statement_line_id;
 END IF;
 IF bank_id IS NULL THEN RETURN NEW; END IF;
 SELECT amount INTO capacity FROM finance_bank_statement_intake_line
 WHERE id=bank_id AND organization_id=org FOR UPDATE;
 SELECT coalesce(sum(allocated_amount),0) INTO used_cash
   FROM finance_cash_allocation_record
   WHERE organization_id=org AND bank_statement_line_id=bank_id;
 SELECT coalesce(sum(amount),0) INTO used_funding
   FROM finance_employee_funding_record
   WHERE organization_id=org AND bank_statement_line_id=bank_id;
 SELECT coalesce(sum(amount),0) INTO used_settlement
   FROM finance_employee_settlement_record
   WHERE organization_id=org AND bank_statement_line_id=bank_id;
 IF capacity IS NULL OR
    used_cash+used_funding+used_settlement>capacity THEN
    RAISE EXCEPTION 'Cross-domain bank movement allocation exceeded'
      USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_bank_shared_cap_cash
AFTER INSERT ON finance_cash_allocation_record FOR EACH ROW
EXECUTE FUNCTION finance_bank_shared_allocation_cap_guard();
CREATE TRIGGER finance_bank_shared_cap_funding
AFTER INSERT ON finance_employee_funding_record FOR EACH ROW
EXECUTE FUNCTION finance_bank_shared_allocation_cap_guard();
CREATE TRIGGER finance_bank_shared_cap_settlement
AFTER INSERT ON finance_employee_settlement_record FOR EACH ROW
EXECUTE FUNCTION finance_bank_shared_allocation_cap_guard();

-- Employee-owned personal expenses need separate approved expense claim
-- recognition + confirmed reimbursement bank debit. Never coalesce with
-- employee borrowing or imprest source. Payroll recovery remains blocked
-- until canonical Phase 9 deduction event and consent are integrated.
