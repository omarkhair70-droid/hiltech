-- Phase 8 employee disbursement/settlement evidence. UNNUMBERED / OFF.
-- Only an independently approved employee draft AND an already observed
-- bank DEBIT can become funded advance or company cash custody. No payout.
-- The initial original policy review is not funding approval.
CREATE TABLE finance_employee_funding_proposal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 draft_kind varchar(24) NOT NULL CHECK(draft_kind IN('EMPLOYEE_ADVANCE','FINANCIAL_IMPREST')),
 employee_draft_id uuid NOT NULL,
 employee_id uuid NOT NULL REFERENCES employee(id) ON DELETE RESTRICT,
 source_policy_approval_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
 bank_statement_line_id uuid NOT NULL,
 amount numeric(18,6) NOT NULL CHECK(amount>0),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 source_fingerprint_sha256 char(64) NOT NULL CHECK(source_fingerprint_sha256 ~ '^[a-f0-9]{64}$'),
 review_version bigint NOT NULL CHECK(review_version>0),
 policy_key varchar(80) NOT NULL CHECK(policy_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
 prepared_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,draft_kind,employee_draft_id),
 FOREIGN KEY(bank_statement_line_id,organization_id)
   REFERENCES finance_bank_statement_intake_line(id,organization_id) ON DELETE RESTRICT
);
CREATE TRIGGER finance_employee_funding_proposal_immutable
BEFORE UPDATE OR DELETE ON finance_employee_funding_proposal FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE TABLE finance_employee_funding_record (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 proposal_id uuid NOT NULL,
 employee_draft_id uuid NOT NULL,
 draft_kind varchar(24) NOT NULL CHECK(draft_kind IN('EMPLOYEE_ADVANCE','FINANCIAL_IMPREST')),
 employee_id uuid NOT NULL REFERENCES employee(id) ON DELETE RESTRICT,
 bank_statement_line_id uuid NOT NULL,
 approval_request_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
 amount numeric(18,6) NOT NULL CHECK(amount>0),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 recorded_at timestamptz NOT NULL,
 correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,proposal_id),
 UNIQUE(organization_id,draft_kind,employee_draft_id),
 FOREIGN KEY(proposal_id,organization_id)
   REFERENCES finance_employee_funding_proposal(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(bank_statement_line_id,organization_id)
   REFERENCES finance_bank_statement_intake_line(id,organization_id) ON DELETE RESTRICT
);
CREATE FUNCTION finance_employee_funding_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_employee_funding_proposal%ROWTYPE;
        bank finance_bank_statement_intake_line%ROWTYPE;
        policy_review approval_request%ROWTYPE;
        funding_review approval_request%ROWTYPE;
        source_amount numeric(18,6);
        source_currency char(3);
        source_employee uuid;
        source_owner_user uuid;
        draft_state varchar(32);
        source_kind varchar(100);
        already_cash numeric(24,6);
        already_funded numeric(24,6);
BEGIN
 SELECT * INTO p FROM finance_employee_funding_proposal
 WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id;
 IF p.id IS NULL OR p.employee_draft_id<>NEW.employee_draft_id
    OR p.draft_kind<>NEW.draft_kind OR p.employee_id<>NEW.employee_id
    OR p.bank_statement_line_id<>NEW.bank_statement_line_id
    OR p.amount<>NEW.amount OR p.currency<>NEW.currency
    OR p.prepared_by_user_id=NEW.recorded_by_user_id THEN
  RAISE EXCEPTION 'Funded employee event differs from reviewed source'
     USING ERRCODE='23514';
 END IF;
 IF p.draft_kind='EMPLOYEE_ADVANCE' THEN
    SELECT requested_amount,currency,employee_id,created_by_user_id,state
    INTO source_amount,source_currency,source_employee,source_owner_user,draft_state
    FROM finance_employee_advance_draft
    WHERE id=p.employee_draft_id AND organization_id=p.organization_id FOR KEY SHARE;
    source_kind:='FINANCE_EMPLOYEE_ADVANCE_POLICY_REVIEW';
 ELSE
    SELECT requested_amount,currency,custodian_employee_id,created_by_user_id,state
    INTO source_amount,source_currency,source_employee,source_owner_user,draft_state
    FROM finance_financial_imprest_draft
    WHERE id=p.employee_draft_id AND organization_id=p.organization_id FOR KEY SHARE;
    source_kind:='FINANCE_FINANCIAL_IMPREST_POLICY_REVIEW';
 END IF;
 IF source_employee IS NULL OR source_employee<>p.employee_id
    OR source_amount<>p.amount OR source_currency<>p.currency
    OR source_owner_user=p.prepared_by_user_id
    OR (p.draft_kind='EMPLOYEE_ADVANCE' AND draft_state<>'DRAFT_POLICY_PENDING')
    OR (p.draft_kind='FINANCIAL_IMPREST' AND draft_state<>'DRAFT_CUSTODY_POLICY_PENDING') THEN
    RAISE EXCEPTION 'Unapproved or changed employee funding source'
       USING ERRCODE='23514';
 END IF;
 SELECT * INTO policy_review FROM approval_request
    WHERE id=p.source_policy_approval_id AND organization_id=p.organization_id;
 IF policy_review.id IS NULL OR policy_review.state<>'APPROVED'
    OR policy_review.subject_type<>source_kind
    OR policy_review.subject_id<>p.employee_draft_id
    OR policy_review.requester_user_id<>source_owner_user
    OR policy_review.completed_at IS NULL
    OR policy_review.completed_at>NEW.recorded_at
    OR NOT EXISTS (
       SELECT 1 FROM approval_decision d
       WHERE d.approval_request_id=policy_review.id
         AND d.decision='APPROVE'
         AND d.subject_version=policy_review.subject_version
         AND d.actor_user_id<>source_owner_user
    ) THEN
    RAISE EXCEPTION 'Employee self-service policy approval is insufficient'
       USING ERRCODE='23514';
 END IF;
 IF p.source_fingerprint_sha256<>encode(sha256(convert_to(
     concat_ws('|','HILTECH_EMPLOYEE_FUNDING_ORIGIN_V1',
       p.organization_id::text,p.draft_kind,p.employee_draft_id::text,
       p.employee_id::text,source_amount::text,source_currency,
       policy_review.id::text,policy_review.subject_version::text
     ),'UTF8')),'hex') THEN
   RAISE EXCEPTION 'Approved original employee draft fingerprint mismatch'
     USING ERRCODE='23514';
 END IF;
 SELECT * INTO funding_review FROM approval_request
    WHERE id=NEW.approval_request_id AND organization_id=NEW.organization_id;
 IF funding_review.id IS NULL OR funding_review.state<>'APPROVED'
    OR funding_review.subject_type<>'FINANCE_EMPLOYEE_FUNDING'
    OR funding_review.subject_id<>p.id
    OR funding_review.subject_version<>p.review_version
    OR funding_review.policy_key<>p.policy_key
    OR funding_review.requester_user_id<>p.prepared_by_user_id
    OR funding_review.requester_user_id=NEW.recorded_by_user_id
    OR funding_review.completed_at IS NULL OR funding_review.completed_at>NEW.recorded_at
    OR NOT EXISTS(
       SELECT 1 FROM approval_decision d
       WHERE d.approval_request_id=funding_review.id
         AND d.decision='APPROVE'
         AND d.subject_version=funding_review.subject_version
         AND d.actor_user_id<>p.prepared_by_user_id
         AND d.actor_user_id<>NEW.recorded_by_user_id)
    OR NOT EXISTS(
       SELECT 1 FROM approval_policy_version v
       WHERE v.organization_id=NEW.organization_id
         AND v.policy_key=p.policy_key
         AND v.version_number=funding_review.policy_version
         AND v.lifecycle_state='ACTIVE' AND v.approval_required=true
         AND v.step_mode='SINGLE'
         AND v.effective_from<=NEW.recorded_at
         AND (v.effective_to IS NULL OR v.effective_to>NEW.recorded_at)) THEN
    RAISE EXCEPTION 'Separate verified funding review required'
       USING ERRCODE='23514';
 END IF;
 SELECT * INTO bank FROM finance_bank_statement_intake_line
   WHERE id=NEW.bank_statement_line_id AND organization_id=NEW.organization_id
   FOR UPDATE;
 IF bank.id IS NULL OR bank.flow<>'DEBIT'
    OR bank.currency<>NEW.currency OR NEW.amount>bank.amount THEN
    RAISE EXCEPTION 'Funding must equal a confirmed company bank debit'
       USING ERRCODE='23514';
 END IF;
 SELECT coalesce(sum(allocated_amount),0) INTO already_cash
   FROM finance_cash_allocation_record
   WHERE organization_id=NEW.organization_id
     AND bank_statement_line_id=NEW.bank_statement_line_id;
 SELECT coalesce(sum(amount),0) INTO already_funded
   FROM finance_employee_funding_record
   WHERE organization_id=NEW.organization_id
     AND bank_statement_line_id=NEW.bank_statement_line_id;
 IF already_cash+already_funded+NEW.amount>bank.amount THEN
    RAISE EXCEPTION 'Company bank debit already used by another funding or AP'
       USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_employee_funding_guard
BEFORE INSERT ON finance_employee_funding_record FOR EACH ROW
EXECUTE FUNCTION finance_employee_funding_guard();
CREATE TRIGGER finance_employee_funding_immutable
BEFORE UPDATE OR DELETE ON finance_employee_funding_record FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

-- Once employee funding events exist, AP/AR cannot reuse their bank debit.
CREATE OR REPLACE FUNCTION finance_cash_allocation_record_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_cash_allocation_proposal%ROWTYPE;
        a approval_request%ROWTYPE;
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
 FROM finance_cash_allocation_record
 WHERE organization_id=NEW.organization_id AND source_kind=NEW.source_kind
   AND source_journal_id=NEW.source_journal_id;
 SELECT coalesce(sum(allocated_amount),0) INTO old_bank_total
 FROM finance_cash_allocation_record
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
 SELECT * INTO a FROM approval_request
 WHERE id=NEW.approval_request_id AND organization_id=NEW.organization_id;
 IF a.id IS NULL OR a.state<>'APPROVED'
    OR a.subject_type<>'FINANCE_AP_AR_CASH_ALLOCATION'
    OR a.subject_id=p.id OR a.subject_version<>p.approval_subject_version
    OR a.policy_key<>p.policy_key
    OR a.requester_user_id<>p.prepared_by_user_id
    OR a.requester_user_id=NEW.recorded_by_user_id
    OR a.completed_at IS NULL OR a.completed_at>NEW.recorded_at
    OR NOT EXISTS(SELECT 1 FROM approval_decision d
        WHERE d.approval_request_id=a.id AND d.decision='APPROVE'
          AND d.subject_version=a.subject_version
          AND d.actor_user_id<>a.requester_user_id
          AND d.actor_user_id<>NEW.recorded_by_user_id)
    OR NOT EXISTS(SELECT 1 FROM approval_policy_version v
        WHERE v.organization_id=NEW.organization_id AND v.policy_key=a.policy_key
          AND v.version_number=a.policy_version
          AND v.lifecycle_state='ACTIVE' AND v.approval_required=true
          AND v.step_mode='SINGLE'
          AND v.effective_from<=NEW.recorded_at
          AND (v.effective_to IS NULL OR v.effective_to>NEW.recorded_at)) THEN
  RAISE EXCEPTION 'Independent original-bound bank allocation approval required'
    USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
-- No employee funding is equivalent to payroll deduction or custody settlement.
