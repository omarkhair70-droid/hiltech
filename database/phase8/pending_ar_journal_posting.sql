-- Phase 8Q: UNNUMBERED AR double entry; requires 8K, 8L, 8N, 8O, 8P.
-- Feature default OFF. No tax amounts, chart accounts or policies seeded.
-- Extend approved company Chart of Accounts to CUSTOMER_RECEIVABLE,
-- SALES_REVENUE, TAX_PAYABLE without reclassifying any historical row.
ALTER TABLE finance_gl_account
 DROP CONSTRAINT finance_gl_account_account_kind_check;
ALTER TABLE finance_gl_account
 ADD CONSTRAINT finance_gl_account_account_kind_check
 CHECK(account_kind IN(
    'SUPPLIER_PAYABLE','EXPENSE','INVENTORY_ASSET','FIXED_ASSET',
    'TAX_RECOVERABLE','OTHER_ASSET','CUSTOMER_RECEIVABLE',
    'SALES_REVENUE','TAX_PAYABLE'
 ));

CREATE TABLE finance_ar_posting_proposal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 issued_invoice_id uuid NOT NULL,
 original_issuance_approval_id uuid NOT NULL
    REFERENCES approval_request(id) ON DELETE RESTRICT,
 source_fingerprint_sha256 char(64) NOT NULL
    CHECK(source_fingerprint_sha256 ~ '^[a-f0-9]{64}$'),
 ledger_fingerprint_sha256 char(64) NOT NULL
    CHECK(ledger_fingerprint_sha256 ~ '^[a-f0-9]{64}$'),
 ledger_subject_version bigint NOT NULL CHECK(ledger_subject_version>0),
 fiscal_period_id uuid NOT NULL,
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 gross_amount numeric(24,6) NOT NULL CHECK(gross_amount>0),
 accounting_treatment_reference varchar(160) NOT NULL
    CHECK(length(btrim(accounting_treatment_reference)) BETWEEN 8 AND 160),
 tax_disposition_reference varchar(160) NOT NULL
    CHECK(length(btrim(tax_disposition_reference)) BETWEEN 8 AND 160),
 tax_evidence_id uuid REFERENCES evidence(id) ON DELETE RESTRICT,
 policy_key varchar(80) NOT NULL CHECK(policy_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
 state varchar(16) NOT NULL DEFAULT 'DRAFT'
    CHECK(state IN('DRAFT','SEALED')),
 created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 sealed_at timestamptz,
 correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,issued_invoice_id),
 FOREIGN KEY(issued_invoice_id,organization_id)
   REFERENCES finance_ar_issued_invoice(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(fiscal_period_id,organization_id)
   REFERENCES finance_gl_period(id,organization_id) ON DELETE RESTRICT,
 CHECK((state='DRAFT' AND sealed_at IS NULL) OR
       (state='SEALED' AND sealed_at IS NOT NULL))
);
CREATE TABLE finance_ar_posting_proposal_line (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL,
 proposal_id uuid NOT NULL,
 line_number integer NOT NULL CHECK(line_number BETWEEN 1 AND 32),
 gl_account_id uuid NOT NULL,
 side varchar(8) NOT NULL CHECK(side IN('DEBIT','CREDIT')),
 amount numeric(24,6) NOT NULL CHECK(amount>0),
 UNIQUE(proposal_id,line_number),
 UNIQUE(proposal_id,gl_account_id),
 FOREIGN KEY(proposal_id,organization_id)
   REFERENCES finance_ar_posting_proposal(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(gl_account_id,organization_id)
   REFERENCES finance_gl_account(id,organization_id) ON DELETE RESTRICT
);
CREATE TRIGGER finance_ar_posting_proposal_line_immutable
BEFORE UPDATE OR DELETE ON finance_ar_posting_proposal_line FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE FUNCTION finance_ar_posting_line_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_ar_posting_proposal%ROWTYPE;
        a finance_gl_account%ROWTYPE;
BEGIN
 SELECT * INTO p FROM finance_ar_posting_proposal
   WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id FOR UPDATE;
 IF NOT FOUND OR p.state<>'DRAFT' THEN
   RAISE EXCEPTION 'Cannot alter sealed AR journal allocation'
     USING ERRCODE='23514';
 END IF;
 SELECT * INTO a FROM finance_gl_account
   WHERE id=NEW.gl_account_id AND organization_id=NEW.organization_id;
 IF NOT FOUND OR a.state<>'APPROVED'
    OR (a.currency IS NOT NULL AND a.currency<>p.currency)
    OR (NEW.side='DEBIT' AND a.account_kind<>'CUSTOMER_RECEIVABLE')
    OR (NEW.side='CREDIT' AND a.account_kind NOT IN('SALES_REVENUE','TAX_PAYABLE')) THEN
   RAISE EXCEPTION 'AR account must match approved company chart and ledger side'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_ar_posting_line_guard
BEFORE INSERT ON finance_ar_posting_proposal_line FOR EACH ROW
EXECUTE FUNCTION finance_ar_posting_line_guard();

CREATE FUNCTION finance_ar_posting_seal_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE sums record;
        ar_count bigint;
        tax_count bigint;
BEGIN
 IF TG_OP='DELETE' THEN
   RAISE EXCEPTION 'AR proposal cannot be deleted' USING ERRCODE='23514';
 END IF;
 IF OLD.state<>'DRAFT' OR NEW.state<>'SEALED' OR NEW.sealed_at IS NULL
    OR ROW(OLD.id,OLD.operation_id,OLD.organization_id,OLD.issued_invoice_id,
       OLD.original_issuance_approval_id,OLD.source_fingerprint_sha256,
       OLD.ledger_fingerprint_sha256,OLD.ledger_subject_version,
       OLD.fiscal_period_id,OLD.currency,OLD.gross_amount,
       OLD.accounting_treatment_reference,OLD.tax_disposition_reference,
       OLD.tax_evidence_id,OLD.policy_key,OLD.created_by_user_id,
       OLD.created_at,OLD.correlation_id) IS DISTINCT FROM
       ROW(NEW.id,NEW.operation_id,NEW.organization_id,NEW.issued_invoice_id,
       NEW.original_issuance_approval_id,NEW.source_fingerprint_sha256,
       NEW.ledger_fingerprint_sha256,NEW.ledger_subject_version,
       NEW.fiscal_period_id,NEW.currency,NEW.gross_amount,
       NEW.accounting_treatment_reference,NEW.tax_disposition_reference,
       NEW.tax_evidence_id,NEW.policy_key,NEW.created_by_user_id,
       NEW.created_at,NEW.correlation_id) THEN
   RAISE EXCEPTION 'Only immutable DRAFT -> SEALED allowed' USING ERRCODE='23514';
 END IF;
 SELECT count(*) AS n, min(line_number) AS min_line, max(line_number) AS max_line,
    coalesce(sum(amount) FILTER(WHERE side='DEBIT'),0) AS dr,
    coalesce(sum(amount) FILTER(WHERE side='CREDIT'),0) AS cr
 INTO sums FROM finance_ar_posting_proposal_line WHERE proposal_id=OLD.id;
 IF sums.n NOT BETWEEN 2 AND 32 OR sums.min_line<>1
    OR sums.max_line<>sums.n OR sums.dr<>sums.cr
    OR sums.dr<>OLD.gross_amount THEN
   RAISE EXCEPTION 'AR allocations must be contiguous and balanced exactly'
     USING ERRCODE='23514';
 END IF;
 SELECT count(*) INTO ar_count FROM finance_ar_posting_proposal_line l
 JOIN finance_gl_account a ON a.id=l.gl_account_id
 WHERE l.proposal_id=OLD.id AND a.account_kind='CUSTOMER_RECEIVABLE'
   AND l.side='DEBIT' AND l.amount=OLD.gross_amount;
 IF ar_count<>1 THEN
   RAISE EXCEPTION 'Exactly one full AR control debit required'
     USING ERRCODE='23514';
 END IF;
 SELECT count(*) INTO tax_count FROM finance_ar_posting_proposal_line l
 JOIN finance_gl_account a ON a.id=l.gl_account_id
 WHERE l.proposal_id=OLD.id AND a.account_kind='TAX_PAYABLE';
 IF (tax_count=0 AND OLD.tax_evidence_id IS NOT NULL) OR
    (tax_count>0 AND OLD.tax_evidence_id IS NULL) THEN
   RAISE EXCEPTION 'Tax allocation requires explicit company tax evidence'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_ar_posting_seal_guard
BEFORE UPDATE OR DELETE ON finance_ar_posting_proposal FOR EACH ROW
EXECUTE FUNCTION finance_ar_posting_seal_guard();

CREATE TABLE finance_ar_posted_journal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 proposal_id uuid NOT NULL UNIQUE,
 issued_invoice_id uuid NOT NULL,
 posting_approval_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
 fiscal_period_id uuid NOT NULL,
 ledger_fingerprint_sha256 char(64) NOT NULL,
 source_fingerprint_sha256 char(64) NOT NULL,
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 gross_amount numeric(24,6) NOT NULL CHECK(gross_amount>0),
 posted_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 posted_at timestamptz NOT NULL,
 correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,issued_invoice_id),
 FOREIGN KEY(proposal_id,organization_id)
   REFERENCES finance_ar_posting_proposal(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(issued_invoice_id,organization_id)
   REFERENCES finance_ar_issued_invoice(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(fiscal_period_id,organization_id)
   REFERENCES finance_gl_period(id,organization_id) ON DELETE RESTRICT
);
CREATE TABLE finance_ar_posted_journal_line (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL,
 journal_id uuid NOT NULL,
 proposal_line_id uuid NOT NULL REFERENCES finance_ar_posting_proposal_line(id) ON DELETE RESTRICT,
 line_number integer NOT NULL CHECK(line_number BETWEEN 1 AND 32),
 gl_account_id uuid NOT NULL,
 side varchar(8) NOT NULL CHECK(side IN('DEBIT','CREDIT')),
 amount numeric(24,6) NOT NULL CHECK(amount>0),
 UNIQUE(journal_id,line_number),
 UNIQUE(journal_id,proposal_line_id),
 FOREIGN KEY(journal_id,organization_id)
  REFERENCES finance_ar_posted_journal(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(gl_account_id,organization_id)
  REFERENCES finance_gl_account(id,organization_id) ON DELETE RESTRICT
);

CREATE FUNCTION finance_ar_posted_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_ar_posting_proposal%ROWTYPE;
        invoice finance_ar_issued_invoice%ROWTYPE;
        approval approval_request%ROWTYPE;
        period finance_gl_period%ROWTYPE;
BEGIN
 SELECT * INTO p FROM finance_ar_posting_proposal
   WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id;
 IF NOT FOUND OR p.state<>'SEALED'
   OR p.issued_invoice_id<>NEW.issued_invoice_id
   OR p.fiscal_period_id<>NEW.fiscal_period_id
   OR p.currency<>NEW.currency
   OR p.gross_amount<>NEW.gross_amount
   OR p.source_fingerprint_sha256<>NEW.source_fingerprint_sha256
   OR p.ledger_fingerprint_sha256<>NEW.ledger_fingerprint_sha256 THEN
   RAISE EXCEPTION 'Posted AR must exactly match reviewed sealed proposal'
     USING ERRCODE='23514';
 END IF;
 SELECT * INTO invoice FROM finance_ar_issued_invoice
   WHERE id=p.issued_invoice_id AND organization_id=p.organization_id;
 IF NOT FOUND OR invoice.gross_amount<>p.gross_amount OR
   invoice.currency<>p.currency OR
   invoice.source_fingerprint_sha256<>p.source_fingerprint_sha256 OR
   invoice.invoice_approval_id<>p.original_issuance_approval_id THEN
   RAISE EXCEPTION 'Source must be immutable independently issued invoice'
     USING ERRCODE='23514';
 END IF;
 SELECT * INTO period FROM finance_gl_period
   WHERE id=p.fiscal_period_id AND organization_id=p.organization_id FOR UPDATE;
 IF NOT FOUND OR period.state<>'OPEN'
   OR (NEW.posted_at AT TIME ZONE 'UTC')::date NOT BETWEEN period.date_start AND period.date_end
   OR EXISTS(SELECT 1 FROM finance_gl_period_close c
       WHERE c.organization_id=p.organization_id AND c.period_id=p.fiscal_period_id) THEN
   RAISE EXCEPTION 'AR cannot post outside approved open financial period'
     USING ERRCODE='23514';
 END IF;
 SELECT * INTO approval FROM approval_request
   WHERE id=NEW.posting_approval_id AND organization_id=p.organization_id;
 IF NOT FOUND OR approval.state<>'APPROVED'
   OR approval.subject_type<>'FINANCE_AR_JOURNAL_POSTING'
   OR approval.subject_id<>p.id
   OR approval.subject_version<>p.ledger_subject_version
   OR approval.policy_key<>p.policy_key
   OR approval.requester_user_id<>p.created_by_user_id
   OR approval.requester_user_id=NEW.posted_by_user_id
   OR approval.completed_at IS NULL OR approval.completed_at>NEW.posted_at
   OR NOT EXISTS(
      SELECT 1 FROM approval_decision d
      WHERE d.approval_request_id=approval.id AND d.decision='APPROVE'
        AND d.subject_version=approval.subject_version
        AND d.decided_at<=NEW.posted_at
        AND d.actor_user_id<>p.created_by_user_id
        AND d.actor_user_id<>NEW.posted_by_user_id)
   OR NOT EXISTS(
      SELECT 1 FROM approval_policy_version v
      WHERE v.organization_id=p.organization_id
        AND v.policy_key=p.policy_key
        AND v.version_number=approval.policy_version
        AND v.approval_required=true AND v.step_mode='SINGLE'
        AND v.lifecycle_state='ACTIVE'
        AND v.effective_from<=NEW.posted_at
        AND (v.effective_to IS NULL OR v.effective_to>NEW.posted_at)) THEN
   RAISE EXCEPTION 'Separate approved exact AR journal posting required'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_ar_posted_guard
BEFORE INSERT ON finance_ar_posted_journal FOR EACH ROW
EXECUTE FUNCTION finance_ar_posted_guard();

CREATE FUNCTION finance_ar_posted_line_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE j finance_ar_posted_journal%ROWTYPE;
        p finance_ar_posting_proposal_line%ROWTYPE;
BEGIN
 SELECT * INTO j FROM finance_ar_posted_journal
   WHERE id=NEW.journal_id AND organization_id=NEW.organization_id;
 SELECT * INTO p FROM finance_ar_posting_proposal_line
   WHERE id=NEW.proposal_line_id AND organization_id=NEW.organization_id;
 IF j.id IS NULL OR p.id IS NULL OR
   p.proposal_id<>j.proposal_id OR p.line_number<>NEW.line_number OR
   p.gl_account_id<>NEW.gl_account_id OR p.side<>NEW.side OR
   p.amount<>NEW.amount THEN
   RAISE EXCEPTION 'AR journal line must exactly copy approved allocation'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_ar_posted_line_guard
BEFORE INSERT ON finance_ar_posted_journal_line FOR EACH ROW
EXECUTE FUNCTION finance_ar_posted_line_guard();

CREATE FUNCTION finance_ar_posted_complete_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE journal_id uuid;
        j finance_ar_posted_journal%ROWTYPE;
        sums record;
        expected bigint;
BEGIN
 IF TG_TABLE_NAME='finance_ar_posted_journal' THEN journal_id:=NEW.id;
 ELSE journal_id:=NEW.journal_id; END IF;
 SELECT * INTO j FROM finance_ar_posted_journal WHERE id=journal_id;
 IF NOT FOUND THEN RETURN NULL; END IF;
 SELECT COUNT(*) AS n,
   COALESCE(SUM(amount) FILTER(WHERE side='DEBIT'),0) AS dr,
   COALESCE(SUM(amount) FILTER(WHERE side='CREDIT'),0) AS cr
 INTO sums FROM finance_ar_posted_journal_line WHERE journal_id=j.id;
 SELECT COUNT(*) INTO expected FROM finance_ar_posting_proposal_line
 WHERE proposal_id=j.proposal_id;
 IF sums.n<>expected OR sums.dr<>sums.cr OR sums.dr<>j.gross_amount THEN
   RAISE EXCEPTION 'Incomplete or unbalanced AR journal posting'
     USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER finance_ar_complete_header
AFTER INSERT ON finance_ar_posted_journal
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION finance_ar_posted_complete_guard();
CREATE CONSTRAINT TRIGGER finance_ar_complete_line
AFTER INSERT ON finance_ar_posted_journal_line
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION finance_ar_posted_complete_guard();
CREATE TRIGGER finance_ar_posted_immutable
BEFORE UPDATE OR DELETE ON finance_ar_posted_journal FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();
CREATE TRIGGER finance_ar_posted_line_immutable
BEFORE UPDATE OR DELETE ON finance_ar_posted_journal_line FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

-- P8L close snapshot V2 currently covers only AP and AP reversals.
-- MUST add AR events to signed closure snapshot before allowing postings.
