-- Phase 8: personally paid employee expense reimbursement. Unnumbered / OFF.
-- An employee's receipt-documentation approval is NOT financial approval.
-- Independently approved bank DEBIT proves reimbursement AFTER it occurred.
-- This is immutable cash settlement evidence, not an external bank payment.
CREATE TABLE finance_expense_reimbursement_proposal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 expense_draft_id uuid NOT NULL,
 employee_id uuid NOT NULL REFERENCES employee(id) ON DELETE RESTRICT,
 documentation_approval_request_id uuid NOT NULL
   REFERENCES approval_request(id) ON DELETE RESTRICT,
 documentation_version bigint NOT NULL CHECK(documentation_version>0),
 bank_statement_line_id uuid NOT NULL,
 amount numeric(18,6) NOT NULL CHECK(amount>0),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 review_version bigint NOT NULL CHECK(review_version>0),
 policy_key varchar(80) NOT NULL CHECK(policy_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
 prepared_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,expense_draft_id,bank_statement_line_id),
 FOREIGN KEY(expense_draft_id,organization_id)
   REFERENCES finance_expense_claim_draft(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(bank_statement_line_id,organization_id)
   REFERENCES finance_bank_statement_intake_line(id,organization_id) ON DELETE RESTRICT
);
CREATE TRIGGER finance_expense_reimbursement_proposal_immutable
BEFORE UPDATE OR DELETE ON finance_expense_reimbursement_proposal FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE TABLE finance_expense_reimbursement_record (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 proposal_id uuid NOT NULL,
 expense_draft_id uuid NOT NULL,
 employee_id uuid NOT NULL REFERENCES employee(id) ON DELETE RESTRICT,
 bank_statement_line_id uuid NOT NULL,
 amount numeric(18,6) NOT NULL CHECK(amount>0),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 approval_request_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
 recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 recorded_at timestamptz NOT NULL,
 correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,proposal_id),
 FOREIGN KEY(proposal_id,organization_id)
   REFERENCES finance_expense_reimbursement_proposal(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(expense_draft_id,organization_id)
   REFERENCES finance_expense_claim_draft(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(bank_statement_line_id,organization_id)
   REFERENCES finance_bank_statement_intake_line(id,organization_id) ON DELETE RESTRICT
);
CREATE FUNCTION finance_expense_reimbursement_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_expense_reimbursement_proposal%ROWTYPE;
        claim finance_expense_claim_draft%ROWTYPE;
        bank finance_bank_statement_intake_line%ROWTYPE;
        reviewed approval_request%ROWTYPE;
        approved approval_request%ROWTYPE;
        used_claim numeric(24,6);
        used_cash numeric(24,6);
        used_funding numeric(24,6);
        used_settlement numeric(24,6);
        used_expense numeric(24,6);
BEGIN
 SELECT * INTO p FROM finance_expense_reimbursement_proposal
 WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id;
 IF p.id IS NULL OR p.expense_draft_id<>NEW.expense_draft_id
    OR p.employee_id<>NEW.employee_id
    OR p.bank_statement_line_id<>NEW.bank_statement_line_id
    OR p.amount<>NEW.amount OR p.currency<>NEW.currency
    OR p.prepared_by_user_id=NEW.recorded_by_user_id THEN
  RAISE EXCEPTION 'Reimbursement differs from immutable Finance proposal'
    USING ERRCODE='23514';
 END IF;
 SELECT * INTO claim FROM finance_expense_claim_draft
 WHERE id=p.expense_draft_id AND organization_id=p.organization_id FOR KEY SHARE;
 IF claim.id IS NULL OR claim.employee_id<>p.employee_id OR
    claim.currency<>p.currency OR claim.state<>'DRAFT_RECEIPT_PENDING' OR
    claim.created_by_user_id=p.prepared_by_user_id THEN
  RAISE EXCEPTION 'Personally paid expense must have separate employee origin'
    USING ERRCODE='23514';
 END IF;
 SELECT * INTO reviewed FROM approval_request
 WHERE id=p.documentation_approval_request_id AND organization_id=p.organization_id;
 IF reviewed.id IS NULL OR reviewed.state<>'APPROVED'
    OR reviewed.subject_type<>'FINANCE_EXPENSE_DOCUMENTATION_REVIEW'
    OR reviewed.subject_id<>p.expense_draft_id
    OR reviewed.subject_version<>p.documentation_version
    OR reviewed.requester_user_id<>claim.created_by_user_id
    OR reviewed.completed_at IS NULL OR reviewed.completed_at>NEW.recorded_at
    OR NOT EXISTS(
      SELECT 1 FROM approval_decision d
      WHERE d.approval_request_id=reviewed.id AND d.decision='APPROVE'
        AND d.subject_version=reviewed.subject_version
        AND d.actor_user_id<>claim.created_by_user_id) THEN
  RAISE EXCEPTION 'Verified employee expense receipts need approved review'
    USING ERRCODE='23514';
 END IF;
 SELECT * INTO bank FROM finance_bank_statement_intake_line
 WHERE id=NEW.bank_statement_line_id AND organization_id=NEW.organization_id
 FOR UPDATE;
 IF bank.id IS NULL OR bank.flow<>'DEBIT' OR
    bank.currency<>NEW.currency THEN
  RAISE EXCEPTION 'A real company bank DEBIT is required to reimburse expense'
    USING ERRCODE='23514';
 END IF;
 SELECT coalesce(sum(amount),0) INTO used_claim
 FROM finance_expense_reimbursement_record
 WHERE organization_id=NEW.organization_id AND expense_draft_id=NEW.expense_draft_id;
 IF used_claim+NEW.amount>claim.amount THEN
  RAISE EXCEPTION 'Employee reimbursement exceeds verified personal expense'
    USING ERRCODE='23514';
 END IF;
 SELECT coalesce(sum(allocated_amount),0) INTO used_cash
 FROM finance_cash_allocation_record
 WHERE organization_id=NEW.organization_id AND bank_statement_line_id=NEW.bank_statement_line_id;
 SELECT coalesce(sum(amount),0) INTO used_funding
 FROM finance_employee_funding_record
 WHERE organization_id=NEW.organization_id AND bank_statement_line_id=NEW.bank_statement_line_id;
 SELECT coalesce(sum(amount),0) INTO used_settlement
 FROM finance_employee_settlement_record
 WHERE organization_id=NEW.organization_id AND bank_statement_line_id=NEW.bank_statement_line_id;
 SELECT coalesce(sum(amount),0) INTO used_expense
 FROM finance_expense_reimbursement_record
 WHERE organization_id=NEW.organization_id AND bank_statement_line_id=NEW.bank_statement_line_id;
 IF used_cash+used_funding+used_settlement+used_expense+NEW.amount>bank.amount THEN
  RAISE EXCEPTION 'Bank payment already allocated elsewhere'
    USING ERRCODE='23514';
 END IF;
 SELECT * INTO approved FROM approval_request
 WHERE id=NEW.approval_request_id AND organization_id=NEW.organization_id;
 IF approved.id IS NULL OR approved.state<>'APPROVED'
    OR approved.subject_type<>'FINANCE_EXPENSE_REIMBURSEMENT'
    OR approved.subject_id<>p.id OR approved.subject_version<>p.review_version
    OR approved.policy_key<>p.policy_key
    OR approved.requester_user_id<>p.prepared_by_user_id
    OR approved.requester_user_id=NEW.recorded_by_user_id
    OR approved.completed_at IS NULL OR approved.completed_at>NEW.recorded_at
    OR NOT EXISTS(
       SELECT 1 FROM approval_decision d
       WHERE d.approval_request_id=approved.id AND d.decision='APPROVE'
         AND d.subject_version=approved.subject_version
         AND d.actor_user_id<>p.prepared_by_user_id
         AND d.actor_user_id<>NEW.recorded_by_user_id)
    OR NOT EXISTS(
       SELECT 1 FROM approval_policy_version v
       WHERE v.organization_id=NEW.organization_id
         AND v.policy_key=p.policy_key AND v.version_number=approved.policy_version
         AND v.lifecycle_state='ACTIVE' AND v.approval_required=true
         AND v.step_mode='SINGLE' AND v.effective_from<=NEW.recorded_at
         AND (v.effective_to IS NULL OR v.effective_to>NEW.recorded_at)) THEN
  RAISE EXCEPTION 'Separate Finance reimbursement signatory is required'
    USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_expense_reimbursement_guard
BEFORE INSERT ON finance_expense_reimbursement_record FOR EACH ROW
EXECUTE FUNCTION finance_expense_reimbursement_guard();
CREATE TRIGGER finance_expense_reimbursement_immutable
BEFORE UPDATE OR DELETE ON finance_expense_reimbursement_record FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

-- Extend the common cap across AP/AR, funding, custody return and expense.
CREATE OR REPLACE FUNCTION finance_bank_shared_allocation_cap_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE bank_id uuid;
        org uuid;
        capacity numeric(24,6);
        used_cash numeric(24,6);
        used_funding numeric(24,6);
        used_settlement numeric(24,6);
        used_expense numeric(24,6);
BEGIN
 org:=NEW.organization_id;
 bank_id:=NEW.bank_statement_line_id;
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
 SELECT coalesce(sum(amount),0) INTO used_expense
 FROM finance_expense_reimbursement_record
 WHERE organization_id=org AND bank_statement_line_id=bank_id;
 IF capacity IS NULL OR
    used_cash+used_funding+used_settlement+used_expense>capacity THEN
  RAISE EXCEPTION 'Bank movement is double-allocated across Finance sources'
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_bank_shared_cap_reimbursement
AFTER INSERT ON finance_expense_reimbursement_record FOR EACH ROW
EXECUTE FUNCTION finance_bank_shared_allocation_cap_guard();

-- A reimbursed employee expense is not evidence that the expense was
-- categorized, tax-reclaimed or posted to GL. Those require canonical
-- chart-of-accounts mapping and fiscal period posting separately.
