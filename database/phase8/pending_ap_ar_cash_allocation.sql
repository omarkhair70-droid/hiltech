-- Phase 8U: UNNUMBERED, DEFAULT OFF, NO EXTERNAL BANK TRANSFER.
-- Separate company-approved allocation of recorded AP/AR accounting source to
-- independently imported bank statement transaction, never a payment command.
-- Depends on 8K/8L/8N/8O/8P/8Q/8S.
CREATE TABLE finance_cash_allocation_proposal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 source_kind varchar(2) NOT NULL CHECK(source_kind IN('AP','AR')),
 source_journal_id uuid NOT NULL,
 bank_statement_line_id uuid NOT NULL,
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 allocated_amount numeric(24,6) NOT NULL CHECK(allocated_amount>0),
 source_journal_sha256 char(64) NOT NULL CHECK(source_journal_sha256 ~ '^[a-f0-9]{64}$'),
 bank_statement_sha256 char(64) NOT NULL CHECK(bank_statement_sha256 ~ '^[a-f0-9]{64}$'),
 approval_subject_version bigint NOT NULL CHECK(approval_subject_version>0),
 policy_key varchar(80) NOT NULL CHECK(policy_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
 allocation_reason varchar(180) NOT NULL CHECK(length(btrim(allocation_reason))>=8),
 prepared_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,source_kind,source_journal_id,bank_statement_line_id)
);
CREATE TRIGGER finance_cash_allocation_proposal_immutable
BEFORE UPDATE OR DELETE ON finance_cash_allocation_proposal FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE TABLE finance_cash_allocation_record (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 proposal_id uuid NOT NULL,
 source_kind varchar(2) NOT NULL CHECK(source_kind IN('AP','AR')),
 source_journal_id uuid NOT NULL,
 bank_statement_line_id uuid NOT NULL,
 approval_request_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
 approval_subject_version bigint NOT NULL,
 policy_key varchar(80) NOT NULL,
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 allocated_amount numeric(24,6) NOT NULL CHECK(allocated_amount>0),
 recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 recorded_at timestamptz NOT NULL,
 correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,proposal_id),
 FOREIGN KEY(proposal_id,organization_id)
  REFERENCES finance_cash_allocation_proposal(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(bank_statement_line_id,organization_id)
  REFERENCES finance_bank_statement_intake_line(id,organization_id) ON DELETE RESTRICT
);
CREATE FUNCTION finance_cash_allocation_record_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_cash_allocation_proposal%ROWTYPE;
        a approval_request%ROWTYPE;
        bank finance_bank_statement_intake_line%ROWTYPE;
        source_amount numeric(24,6);
        source_currency char(3);
        source_hash char(64);
        old_source_total numeric(24,6);
        old_bank_total numeric(24,6);
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

 -- Lock source journal and bank transaction BEFORE checking the two totals.
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
    SELECT 1 FROM finance_ap_journal_reversal rv
    WHERE rv.organization_id=NEW.organization_id
      AND rv.original_journal_id=NEW.source_journal_id
 ) THEN
   RAISE EXCEPTION 'Reversed AP cannot be allocated to new bank payment'
     USING ERRCODE='23514';
 END IF;
 IF source_matched IS NULL OR source_currency<>NEW.currency
    OR source_hash<>p.source_journal_sha256 THEN
   RAISE EXCEPTION 'Recognized current immutable supplier/customer journal required'
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
   RAISE EXCEPTION 'Exact approved bank credit/debit required'
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
 IF old_source_total+NEW.allocated_amount>source_amount
    OR old_bank_total+NEW.allocated_amount>bank.amount THEN
   RAISE EXCEPTION 'Bank or source cannot be over-allocated'
     USING ERRCODE='23514';
 END IF;

 SELECT * INTO a FROM approval_request
 WHERE id=NEW.approval_request_id AND organization_id=NEW.organization_id;
 IF a.id IS NULL OR a.state<>'APPROVED'
    OR a.subject_type<>'FINANCE_AP_AR_CASH_ALLOCATION'
    OR a.subject_id=p.id OR a.subject_version=p.approval_subject_version
    OR a.policy_key=p.policy_key
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
CREATE TRIGGER finance_cash_allocation_record_guard
BEFORE INSERT ON finance_cash_allocation_record FOR EACH ROW
EXECUTE FUNCTION finance_cash_allocation_record_guard();
CREATE TRIGGER finance_cash_allocation_record_immutable
BEFORE UPDATE OR DELETE ON finance_cash_allocation_record FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

-- This is an accounting allocation of independently imported bank evidence;
-- no bank payouts are sent by HILTECH. Partial balances must always be
-- calculated from immutable posted journals MINUS recorded allocations.
-- Reversals and corrections of cash allocations need a separate audited flow.
