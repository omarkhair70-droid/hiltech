-- Phase 8S: UNNUMBERED. Manual bank statement intake for approved company
-- bank accounts, NOT proof of an external payment or a bank connection.
-- No bank accounts, company authorities or data are seeded.
CREATE TABLE finance_bank_account_register (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 bank_name varchar(160) NOT NULL CHECK(length(btrim(bank_name))>=3),
 masked_account_reference varchar(64) NOT NULL
    CHECK(length(btrim(masked_account_reference)) BETWEEN 4 AND 64),
 company_approval_reference varchar(180) NOT NULL
    CHECK(length(btrim(company_approval_reference))>=8),
 state varchar(16) NOT NULL CHECK(state IN('APPROVED','RETIRED')),
 created_at timestamptz NOT NULL,
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,masked_account_reference)
);
CREATE TRIGGER finance_bank_account_register_immutable
BEFORE UPDATE OR DELETE ON finance_bank_account_register FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE TABLE finance_bank_statement_intake_proposal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 account_id uuid NOT NULL,
 statement_evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
 statement_sha256 char(64) NOT NULL CHECK(statement_sha256 ~ '^[a-f0-9]{64}$'),
 statement_lines_sha256 char(64) NOT NULL CHECK(statement_lines_sha256 ~ '^[a-f0-9]{64}$'),
 date_start date NOT NULL,
 date_end date NOT NULL,
 opening_balance numeric(24,6) NOT NULL,
 closing_balance numeric(24,6) NOT NULL,
 expected_line_count integer NOT NULL CHECK(expected_line_count BETWEEN 1 AND 10000),
 source_version bigint NOT NULL CHECK(source_version>0),
 policy_key varchar(80) NOT NULL CHECK(policy_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
 prepared_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,account_id,statement_sha256),
 FOREIGN KEY(account_id,organization_id)
  REFERENCES finance_bank_account_register(id,organization_id) ON DELETE RESTRICT,
 CHECK(date_start<=date_end)
);
CREATE TRIGGER finance_bank_statement_intake_proposal_immutable
BEFORE UPDATE OR DELETE ON finance_bank_statement_intake_proposal FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();


-- Prepared lines are sealed as append-only review evidence; the operator
-- imports exact matching lines, not arbitrary rows supplied after approval.
CREATE TABLE finance_bank_statement_intake_proposal_line (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL,
 proposal_id uuid NOT NULL,
 line_number integer NOT NULL CHECK(line_number BETWEEN 1 AND 10000),
 external_transaction_id varchar(180) NOT NULL
  CHECK(length(btrim(external_transaction_id)) BETWEEN 5 AND 180),
 booked_on date NOT NULL,
 flow varchar(6) NOT NULL CHECK(flow IN('DEBIT','CREDIT')),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 amount numeric(24,6) NOT NULL CHECK(amount>0),
 original_line_sha256 char(64) NOT NULL CHECK(original_line_sha256 ~ '^[a-f0-9]{64}$'),
 UNIQUE(proposal_id,line_number),
 UNIQUE(proposal_id,external_transaction_id),
 UNIQUE(organization_id,proposal_id,id),
 FOREIGN KEY(proposal_id,organization_id)
  REFERENCES finance_bank_statement_intake_proposal(id,organization_id) ON DELETE RESTRICT
);
CREATE TRIGGER finance_bank_intake_proposal_line_immutable
BEFORE UPDATE OR DELETE ON finance_bank_statement_intake_proposal_line FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE FUNCTION finance_bank_proposal_line_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_bank_statement_intake_proposal%ROWTYPE;
        a finance_bank_account_register%ROWTYPE;
BEGIN
 SELECT * INTO p FROM finance_bank_statement_intake_proposal
   WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id FOR UPDATE;
 SELECT * INTO a FROM finance_bank_account_register
   WHERE id=p.account_id AND organization_id=NEW.organization_id;
 IF p.id IS NULL OR a.id IS NULL OR a.state<>'APPROVED'
   OR NEW.booked_on NOT BETWEEN p.date_start AND p.date_end
   OR NEW.currency<>a.currency THEN
  RAISE EXCEPTION 'Bank proposal lines must match approved account and dates'
    USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_bank_proposal_line_guard
BEFORE INSERT ON finance_bank_statement_intake_proposal_line FOR EACH ROW
EXECUTE FUNCTION finance_bank_proposal_line_guard();

CREATE FUNCTION finance_bank_proposal_complete_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_bank_statement_intake_proposal%ROWTYPE;
        target uuid;
        n bigint;
        total numeric(24,6);
        hash char(64);
BEGIN
 IF TG_TABLE_NAME='finance_bank_statement_intake_proposal' THEN target:=NEW.id;
 ELSE target:=NEW.proposal_id; END IF;
 SELECT * INTO p FROM finance_bank_statement_intake_proposal WHERE id=target;
 IF p.id IS NULL THEN RETURN NULL; END IF;
 SELECT count(*),
   coalesce(sum(CASE WHEN flow='CREDIT' THEN amount ELSE -amount END),0)
   INTO n,total FROM finance_bank_statement_intake_proposal_line
   WHERE proposal_id=p.id;
 SELECT encode(sha256(convert_to(COALESCE(
   string_agg(concat_ws(':',external_transaction_id,booked_on::text,flow,
     currency,amount::text,original_line_sha256),'|' ORDER BY external_transaction_id),
   'EMPTY'),'UTF8')),'hex') INTO hash
 FROM finance_bank_statement_intake_proposal_line WHERE proposal_id=p.id;
 IF n<>p.expected_line_count OR hash<>p.statement_lines_sha256
   OR p.opening_balance+total<>p.closing_balance THEN
  RAISE EXCEPTION 'Prepared bank statement must match immutable line checksum and balances'
    USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER finance_bank_proposal_complete_header
AFTER INSERT ON finance_bank_statement_intake_proposal
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION finance_bank_proposal_complete_guard();
CREATE CONSTRAINT TRIGGER finance_bank_proposal_complete_line
AFTER INSERT ON finance_bank_statement_intake_proposal_line
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION finance_bank_proposal_complete_guard();

CREATE TABLE finance_bank_statement_intake (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 proposal_id uuid NOT NULL,
 account_id uuid NOT NULL,
 approval_request_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
 statement_sha256 char(64) NOT NULL,
 statement_lines_sha256 char(64) NOT NULL CHECK(statement_lines_sha256 ~ '^[a-f0-9]{64}$'),
 statement_evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 date_start date NOT NULL,
 date_end date NOT NULL,
 opening_balance numeric(24,6) NOT NULL,
 closing_balance numeric(24,6) NOT NULL,
 expected_line_count integer NOT NULL CHECK(expected_line_count BETWEEN 1 AND 10000),
 imported_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 imported_at timestamptz NOT NULL,
 correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,proposal_id),
 UNIQUE(organization_id,account_id,statement_sha256),
 FOREIGN KEY(proposal_id,organization_id)
  REFERENCES finance_bank_statement_intake_proposal(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(account_id,organization_id)
  REFERENCES finance_bank_account_register(id,organization_id) ON DELETE RESTRICT,
 CHECK(date_start<=date_end)
);
CREATE TABLE finance_bank_statement_intake_line (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL,
 intake_id uuid NOT NULL,
 account_id uuid NOT NULL,
 external_transaction_id varchar(180) NOT NULL
  CHECK(length(btrim(external_transaction_id)) BETWEEN 5 AND 180),
 booked_on date NOT NULL,
 flow varchar(6) NOT NULL CHECK(flow IN('DEBIT','CREDIT')),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 amount numeric(24,6) NOT NULL CHECK(amount>0),
 original_line_sha256 char(64) NOT NULL CHECK(original_line_sha256 ~ '^[a-f0-9]{64}$'),
 UNIQUE(organization_id,account_id,external_transaction_id),
 UNIQUE(organization_id,intake_id,id),
 UNIQUE(id,organization_id),
 UNIQUE(intake_id,original_line_sha256),
 FOREIGN KEY(intake_id,organization_id)
  REFERENCES finance_bank_statement_intake(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(account_id,organization_id)
  REFERENCES finance_bank_account_register(id,organization_id) ON DELETE RESTRICT
);
CREATE FUNCTION finance_bank_intake_header_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_bank_statement_intake_proposal%ROWTYPE;
        a finance_bank_account_register%ROWTYPE;
        r approval_request%ROWTYPE;
BEGIN
 SELECT * INTO p FROM finance_bank_statement_intake_proposal
 WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id;
 SELECT * INTO a FROM finance_bank_account_register
 WHERE id=NEW.account_id AND organization_id=NEW.organization_id;
 IF p.id IS NULL OR a.id IS NULL OR a.state<>'APPROVED'
   OR p.account_id<>NEW.account_id
   OR p.statement_sha256<>NEW.statement_sha256
   OR p.statement_lines_sha256<>NEW.statement_lines_sha256
   OR p.statement_evidence_id<>NEW.statement_evidence_id
   OR p.date_start<>NEW.date_start OR p.date_end<>NEW.date_end
   OR p.opening_balance<>NEW.opening_balance
   OR p.closing_balance<>NEW.closing_balance
   OR p.expected_line_count<>NEW.expected_line_count
   OR p.prepared_by_user_id=NEW.imported_by_user_id
   OR a.currency<>NEW.currency THEN
   RAISE EXCEPTION 'Imported bank statement must equal independently reviewed source'
     USING ERRCODE='23514';
 END IF;
 SELECT * INTO r FROM approval_request
 WHERE id=NEW.approval_request_id AND organization_id=NEW.organization_id;
 IF r.id IS NULL OR r.state<>'APPROVED'
   OR r.subject_type<>'FINANCE_BANK_STATEMENT_INTAKE'
   OR r.subject_id<>p.id OR r.subject_version<>p.source_version
   OR r.policy_key<>p.policy_key
   OR r.requester_user_id<>p.prepared_by_user_id
   OR r.requester_user_id=NEW.imported_by_user_id
   OR r.completed_at IS NULL OR r.completed_at>NEW.imported_at
   OR NOT EXISTS(
     SELECT 1 FROM approval_decision d
     WHERE d.approval_request_id=r.id AND d.decision='APPROVE'
       AND d.subject_version=r.subject_version
       AND d.actor_user_id<>r.requester_user_id
       AND d.actor_user_id<>NEW.imported_by_user_id)
   OR NOT EXISTS(
     SELECT 1 FROM approval_policy_version v
     WHERE v.organization_id=NEW.organization_id
       AND v.policy_key=r.policy_key
       AND v.version_number=r.policy_version
       AND v.lifecycle_state='ACTIVE' AND v.approval_required=true
       AND v.step_mode='SINGLE'
       AND v.effective_from<=NEW.imported_at
       AND (v.effective_to IS NULL OR v.effective_to>NEW.imported_at))
 THEN RAISE EXCEPTION 'Independent company bank intake approval required'
      USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS(
   SELECT 1 FROM evidence e JOIN evidence_upload_session u
      ON u.evidence_id=e.id
   WHERE e.id=NEW.statement_evidence_id
     AND e.organization_id=NEW.organization_id
     AND e.target_type='FINANCE_BANK_STATEMENT_INTAKE'
     AND e.target_id=NEW.proposal_id
     AND e.sha256=NEW.statement_sha256
     AND e.storage_state='READY' AND u.state='READY'
     AND e.finalized_at IS NOT NULL AND u.finalized_at IS NOT NULL
     AND e.object_key_ref IS NOT NULL
     AND e.sha256=u.expected_sha256 AND e.size_bytes=u.expected_size_bytes
     AND e.classification_code='HIGHLY_RESTRICTED'
     AND e.client_visibility_mode='INTERNAL_ONLY')
 THEN RAISE EXCEPTION 'Protected finalized source bank statement required'
      USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_bank_intake_header_guard
BEFORE INSERT ON finance_bank_statement_intake FOR EACH ROW
EXECUTE FUNCTION finance_bank_intake_header_guard();
CREATE FUNCTION finance_bank_intake_line_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE h finance_bank_statement_intake%ROWTYPE;
BEGIN
 SELECT * INTO h FROM finance_bank_statement_intake
 WHERE id=NEW.intake_id AND organization_id=NEW.organization_id;
 IF h.id IS NULL OR h.account_id<>NEW.account_id
    OR h.currency<>NEW.currency
    OR NEW.booked_on NOT BETWEEN h.date_start AND h.date_end
    OR NOT EXISTS(
        SELECT 1 FROM finance_bank_statement_intake_proposal_line pl
        WHERE pl.organization_id=NEW.organization_id
          AND pl.proposal_id=h.proposal_id
          AND pl.external_transaction_id=NEW.external_transaction_id
          AND pl.booked_on=NEW.booked_on AND pl.flow=NEW.flow
          AND pl.currency=NEW.currency AND pl.amount=NEW.amount
          AND pl.original_line_sha256=NEW.original_line_sha256
       ) THEN
  RAISE EXCEPTION 'Bank statement line outside exact approved source'
    USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_bank_intake_line_guard
BEFORE INSERT ON finance_bank_statement_intake_line FOR EACH ROW
EXECUTE FUNCTION finance_bank_intake_line_guard();
CREATE FUNCTION finance_bank_intake_complete_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE h finance_bank_statement_intake%ROWTYPE;
        n bigint;
        balance numeric(24,6);
        actual_lines_sha256 char(64);
        target uuid;
BEGIN
 IF TG_TABLE_NAME='finance_bank_statement_intake' THEN target:=NEW.id;
 ELSE target:=NEW.intake_id; END IF;
 SELECT * INTO h FROM finance_bank_statement_intake WHERE id=target;
 IF h.id IS NULL THEN RETURN NULL; END IF;
 SELECT count(*),coalesce(sum(CASE WHEN flow='CREDIT' THEN amount ELSE -amount END),0)
 INTO n,balance FROM finance_bank_statement_intake_line WHERE intake_id=h.id;
 SELECT encode(sha256(convert_to(COALESCE(
   string_agg(concat_ws(':',
       external_transaction_id,booked_on::text,flow,currency,
       amount::text,original_line_sha256),'|' ORDER BY external_transaction_id),
   'EMPTY'),'UTF8')),'hex') INTO actual_lines_sha256
 FROM finance_bank_statement_intake_line WHERE intake_id=h.id;
 IF n<>h.expected_line_count OR
    actual_lines_sha256<>h.statement_lines_sha256 OR
    h.opening_balance+balance<>h.closing_balance THEN
  RAISE EXCEPTION 'Bank statement count and balances must tie exactly'
    USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER finance_bank_intake_complete_header
AFTER INSERT ON finance_bank_statement_intake
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION finance_bank_intake_complete_guard();
CREATE CONSTRAINT TRIGGER finance_bank_intake_complete_line
AFTER INSERT ON finance_bank_statement_intake_line
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION finance_bank_intake_complete_guard();
CREATE TRIGGER finance_bank_intake_immutable
BEFORE UPDATE OR DELETE ON finance_bank_statement_intake FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();
CREATE TRIGGER finance_bank_intake_line_immutable
BEFORE UPDATE OR DELETE ON finance_bank_statement_intake_line FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

-- A manually approved bank statement is evidence for accounting review, not
-- cryptographic proof of an executed payment, a bank API connection, or a
-- reconciled Finance event. Cash allocation needs separate source review.
