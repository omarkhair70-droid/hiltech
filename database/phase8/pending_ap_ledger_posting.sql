-- Phase 8K Finance AP: UNNUMBERED integration proposal. DevOps assigns Flyway
-- version after ALL P7/P8/P9/P10/P11/P13 migrations and review.
-- This is an isolated posting ledger, NOT an account chart populated
-- with imagined tax codes or an automatically payable supplier invoice.
-- All rows in configuration tables require actual signed company finance policy.
-- Never enable production posting until exact-source ledger approvals and
-- real tax/accounting rules are verified.

CREATE TABLE finance_gl_account (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    account_code varchar(64) NOT NULL,
    account_name varchar(180) NOT NULL,
    account_kind varchar(32) NOT NULL CHECK (account_kind IN (
        'SUPPLIER_PAYABLE', 'EXPENSE', 'INVENTORY_ASSET', 'FIXED_ASSET',
        'TAX_RECOVERABLE', 'OTHER_ASSET'
    )),
    currency char(3) NULL CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    state varchar(16) NOT NULL CHECK (state IN ('APPROVED','RETIRED')),
    approved_company_chart_reference varchar(160) NOT NULL
      CHECK(length(btrim(approved_company_chart_reference)) >= 8),
    created_at timestamptz NOT NULL,
    UNIQUE(organization_id, id),
    UNIQUE(organization_id, account_code)
);

CREATE TABLE finance_gl_period (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    date_start date NOT NULL,
    date_end date NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('OPEN','CLOSED')),
    approved_company_period_reference varchar(160) NOT NULL
      CHECK(length(btrim(approved_company_period_reference)) >= 8),
    created_at timestamptz NOT NULL,
    CHECK (date_start <= date_end),
    UNIQUE(organization_id,id)
);

-- An exact source and accountant-typed balance proposal is reviewed
-- INDEPENDENTLY from the supplier's receipt-document review.
CREATE TABLE finance_ap_posting_proposal (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    payable_candidate_id uuid NOT NULL
      REFERENCES finance_payable_candidate(id) ON DELETE RESTRICT,
    supplier_invoice_id uuid NOT NULL,
    supplier_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    original_accounting_approval_id uuid NOT NULL
      REFERENCES approval_request(id) ON DELETE RESTRICT,
    invoice_source_sha256 char(64) NOT NULL
      CHECK(invoice_source_sha256 ~ '^[a-f0-9]{64}$'),
    invoice_review_source_version bigint NOT NULL CHECK(invoice_review_source_version >= 1),
    ledger_fingerprint_sha256 char(64) NOT NULL
      CHECK(ledger_fingerprint_sha256 ~ '^[a-f0-9]{64}$'),
    ledger_subject_version bigint NOT NULL CHECK(ledger_subject_version >= 1),
    fiscal_period_id uuid NOT NULL,
    currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
    gross_amount numeric(24,6) NOT NULL CHECK(gross_amount > 0),
    accounting_treatment_reference varchar(160) NOT NULL
      CHECK(length(btrim(accounting_treatment_reference)) >= 8),
    accounting_tax_disposition varchar(56) NOT NULL
      CHECK(accounting_tax_disposition IN (
        'TAX_REVIEWED_NO_RECOVERABLE_TAX',
        'TAX_REVIEWED_RECOVERABLE_WITH_EVIDENCE'
      )),
    tax_evidence_id uuid NULL REFERENCES evidence(id) ON DELETE RESTRICT,
    posting_policy_key varchar(80) NOT NULL CHECK(posting_policy_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    state varchar(16) NOT NULL DEFAULT 'DRAFT'
      CHECK(state IN ('DRAFT','SEALED')),
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    sealed_at timestamptz NULL,
    correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id)) > 0),
    UNIQUE(organization_id,id),
    FOREIGN KEY (fiscal_period_id,organization_id)
      REFERENCES finance_gl_period(id,organization_id) ON DELETE RESTRICT,
    CHECK(
      (state='DRAFT' AND sealed_at IS NULL) OR
      (state='SEALED' AND sealed_at IS NOT NULL)
    ),
    CHECK(
       (accounting_tax_disposition='TAX_REVIEWED_RECOVERABLE_WITH_EVIDENCE'
         AND tax_evidence_id IS NOT NULL) OR
       (accounting_tax_disposition='TAX_REVIEWED_NO_RECOVERABLE_TAX'
         AND tax_evidence_id IS NULL)
    )
);

CREATE TABLE finance_ap_posting_proposal_line (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    proposal_id uuid NOT NULL,
    line_number integer NOT NULL CHECK(line_number BETWEEN 1 AND 32),
    gl_account_id uuid NOT NULL,
    side varchar(8) NOT NULL CHECK(side IN ('DEBIT','CREDIT')),
    amount numeric(24,6) NOT NULL CHECK(amount > 0),
    UNIQUE(proposal_id,line_number),
    UNIQUE(organization_id,proposal_id,id),
    FOREIGN KEY(proposal_id,organization_id)
      REFERENCES finance_ap_posting_proposal(id,organization_id) ON DELETE RESTRICT,
    FOREIGN KEY(gl_account_id,organization_id)
      REFERENCES finance_gl_account(id,organization_id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX finance_ap_one_account_per_proposal
  ON finance_ap_posting_proposal_line(proposal_id,gl_account_id);

-- Real, immutable posted accounting event; a review-only approval or a
-- draft AP proposal is not exposed here as a recognized liability.
CREATE TABLE finance_ap_posted_journal (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    proposal_id uuid NOT NULL UNIQUE REFERENCES finance_ap_posting_proposal(id) ON DELETE RESTRICT,
    payable_candidate_id uuid NOT NULL UNIQUE
      REFERENCES finance_payable_candidate(id) ON DELETE RESTRICT,
    journal_posting_approval_id uuid NOT NULL
      REFERENCES approval_request(id) ON DELETE RESTRICT,
    fiscal_period_id uuid NOT NULL,
    invoice_source_sha256 char(64) NOT NULL,
    ledger_fingerprint_sha256 char(64) NOT NULL,
    currency char(3) NOT NULL,
    gross_amount numeric(24,6) NOT NULL CHECK(gross_amount > 0),
    posted_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    posted_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id)) > 0),
    UNIQUE(organization_id,id),
    FOREIGN KEY(fiscal_period_id,organization_id)
      REFERENCES finance_gl_period(id,organization_id) ON DELETE RESTRICT
);

CREATE TABLE finance_ap_posted_journal_line (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    journal_id uuid NOT NULL,
    proposal_line_id uuid NOT NULL,
    line_number integer NOT NULL CHECK(line_number BETWEEN 1 AND 32),
    gl_account_id uuid NOT NULL,
    side varchar(8) NOT NULL CHECK(side IN ('DEBIT','CREDIT')),
    amount numeric(24,6) NOT NULL CHECK(amount > 0),
    UNIQUE(journal_id,proposal_line_id),
    UNIQUE(journal_id,line_number),
    FOREIGN KEY(journal_id,organization_id)
      REFERENCES finance_ap_posted_journal(id,organization_id) ON DELETE RESTRICT,
    FOREIGN KEY(gl_account_id,organization_id)
      REFERENCES finance_gl_account(id,organization_id) ON DELETE RESTRICT
);

CREATE INDEX idx_finance_ap_posted_org_date
  ON finance_ap_posted_journal(organization_id,posted_at DESC);
CREATE INDEX idx_finance_ap_posted_liability
  ON finance_ap_posted_journal(organization_id,payable_candidate_id);

-- SQL writes to already-sealed proposals, ledger accounts and journal
-- records cannot rewrite prior accounting history.
CREATE FUNCTION finance_ap_reject_snapshot_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Finance accounting history is immutable; require audited reversal'
    USING ERRCODE='23514';
END; $$;

CREATE TRIGGER finance_gl_account_immutable BEFORE UPDATE OR DELETE
ON finance_gl_account FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();
CREATE TRIGGER finance_gl_period_immutable BEFORE UPDATE OR DELETE
ON finance_gl_period FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();
CREATE TRIGGER finance_ap_proposal_line_immutable BEFORE UPDATE OR DELETE
ON finance_ap_posting_proposal_line FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();
CREATE TRIGGER finance_ap_posted_immutable BEFORE UPDATE OR DELETE
ON finance_ap_posted_journal FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();
CREATE TRIGGER finance_ap_posted_line_immutable BEFORE UPDATE OR DELETE
ON finance_ap_posted_journal_line FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE FUNCTION finance_ap_proposal_line_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_ap_posting_proposal%ROWTYPE; a finance_gl_account%ROWTYPE;
BEGIN
  SELECT * INTO p FROM finance_ap_posting_proposal
    WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id FOR UPDATE;
  IF NOT FOUND OR p.state <> 'DRAFT' THEN
    RAISE EXCEPTION 'Cannot modify sealed Finance posting proposal'
      USING ERRCODE='23514';
  END IF;
  SELECT * INTO a FROM finance_gl_account
    WHERE id=NEW.gl_account_id AND organization_id=NEW.organization_id;
  IF NOT FOUND OR a.state <> 'APPROVED' OR
     (a.currency IS NOT NULL AND a.currency<>p.currency) THEN
    RAISE EXCEPTION 'Company chart account not approved for journal currency'
      USING ERRCODE='23514';
  END IF;
  IF (NEW.side='CREDIT' AND a.account_kind<>'SUPPLIER_PAYABLE')
     OR (NEW.side='DEBIT' AND a.account_kind='SUPPLIER_PAYABLE') THEN
    RAISE EXCEPTION 'Invalid supplier payable debit/credit classification'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER finance_ap_proposal_line_guard
BEFORE INSERT ON finance_ap_posting_proposal_line FOR EACH ROW
EXECUTE FUNCTION finance_ap_proposal_line_guard();

CREATE FUNCTION finance_ap_proposal_seal_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE sums record; n integer; credit_count integer; tax_count integer;
BEGIN
 IF TG_OP='DELETE' THEN
   RAISE EXCEPTION 'Finance posting proposal immutable' USING ERRCODE='23514';
 END IF;
 IF OLD.state<>'DRAFT' OR NEW.state<>'SEALED' OR NEW.sealed_at IS NULL OR
    ROW(NEW.id,NEW.operation_id,NEW.organization_id,NEW.payable_candidate_id,
      NEW.supplier_invoice_id,NEW.supplier_organization_id,
      NEW.original_accounting_approval_id,NEW.invoice_source_sha256,
      NEW.invoice_review_source_version,NEW.ledger_fingerprint_sha256,
      NEW.ledger_subject_version,NEW.fiscal_period_id,NEW.currency,
      NEW.gross_amount,NEW.accounting_treatment_reference,
      NEW.accounting_tax_disposition,NEW.tax_evidence_id,
      NEW.posting_policy_key,NEW.created_by_user_id,NEW.created_at,
      NEW.correlation_id)
    IS DISTINCT FROM
    ROW(OLD.id,OLD.operation_id,OLD.organization_id,OLD.payable_candidate_id,
      OLD.supplier_invoice_id,OLD.supplier_organization_id,
      OLD.original_accounting_approval_id,OLD.invoice_source_sha256,
      OLD.invoice_review_source_version,OLD.ledger_fingerprint_sha256,
      OLD.ledger_subject_version,OLD.fiscal_period_id,OLD.currency,
      OLD.gross_amount,OLD.accounting_treatment_reference,
      OLD.accounting_tax_disposition,OLD.tax_evidence_id,
      OLD.posting_policy_key,OLD.created_by_user_id,OLD.created_at,
      OLD.correlation_id) THEN
   RAISE EXCEPTION 'Only single DRAFT to SEALED transition is permitted'
     USING ERRCODE='23514';
 END IF;
 SELECT COUNT(*) AS line_count,
   MIN(l.line_number) AS min_number, MAX(l.line_number) AS max_number,
   COALESCE(SUM(l.amount) FILTER(WHERE l.side='DEBIT'),0) AS debit,
   COALESCE(SUM(l.amount) FILTER(WHERE l.side='CREDIT'),0) AS credit
 INTO sums FROM finance_ap_posting_proposal_line l WHERE l.proposal_id=OLD.id;
 IF sums.line_count NOT BETWEEN 2 AND 32 OR
    sums.min_number<>1 OR sums.max_number<>sums.line_count OR
    sums.debit<>sums.credit OR sums.credit<>OLD.gross_amount THEN
   RAISE EXCEPTION 'Journal proposal is not completely balanced/ordered'
     USING ERRCODE='23514';
 END IF;
 SELECT COUNT(*) INTO credit_count
 FROM finance_ap_posting_proposal_line l
 JOIN finance_gl_account a ON a.id=l.gl_account_id
 WHERE l.proposal_id=OLD.id AND l.side='CREDIT'
   AND a.account_kind='SUPPLIER_PAYABLE' AND l.amount=OLD.gross_amount;
 IF credit_count<>1 THEN
   RAISE EXCEPTION 'Exactly one full-gross AP credit is required'
     USING ERRCODE='23514';
 END IF;
 SELECT COUNT(*) INTO tax_count
 FROM finance_ap_posting_proposal_line l
 JOIN finance_gl_account a ON a.id=l.gl_account_id
 WHERE l.proposal_id=OLD.id AND l.side='DEBIT'
   AND a.account_kind='TAX_RECOVERABLE';
 IF (OLD.accounting_tax_disposition='TAX_REVIEWED_RECOVERABLE_WITH_EVIDENCE'
     AND tax_count=0) OR
    (OLD.accounting_tax_disposition='TAX_REVIEWED_NO_RECOVERABLE_TAX'
     AND tax_count<>0) THEN
   RAISE EXCEPTION 'Tax disposition and accounting allocation mismatch'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_ap_proposal_seal_guard BEFORE UPDATE OR DELETE
ON finance_ap_posting_proposal FOR EACH ROW
EXECUTE FUNCTION finance_ap_proposal_seal_guard();

-- Direct SQL cannot forge posted Finance debt with merely a document-review
-- approval, an unapproved journal or a stale source/accounting period.
CREATE FUNCTION finance_ap_posted_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
 p finance_ap_posting_proposal%ROWTYPE;
 c finance_payable_candidate%ROWTYPE;
 r approval_request%ROWTYPE;
 d approval_request%ROWTYPE;
 posting_period finance_gl_period%ROWTYPE;
BEGIN
 SELECT * INTO p FROM finance_ap_posting_proposal WHERE id=NEW.proposal_id;
 IF NOT FOUND OR p.state<>'SEALED' OR p.organization_id<>NEW.organization_id
    OR p.payable_candidate_id<>NEW.payable_candidate_id
    OR p.invoice_source_sha256<>NEW.invoice_source_sha256
    OR p.ledger_fingerprint_sha256<>NEW.ledger_fingerprint_sha256
    OR p.fiscal_period_id<>NEW.fiscal_period_id
    OR p.currency<>NEW.currency OR p.gross_amount<>NEW.gross_amount THEN
    RAISE EXCEPTION 'Posted source does not equal sealed journal proposal'
      USING ERRCODE='23514';
 END IF;
 SELECT * INTO c FROM finance_payable_candidate
   WHERE id=p.payable_candidate_id AND organization_id=p.organization_id;
 IF NOT FOUND OR c.state<>'ACCOUNTING_REVIEW_REQUIRED'
    OR c.source_sha256<>p.invoice_source_sha256
    OR c.gross_amount<>p.gross_amount
    OR c.currency<>p.currency
    OR c.supplier_invoice_id<>p.supplier_invoice_id
    OR c.supplier_organization_id<>p.supplier_organization_id THEN
   RAISE EXCEPTION 'Posting source must equal verified supplier invoice'
     USING ERRCODE='23514';
 END IF;
 SELECT * INTO d FROM approval_request WHERE id=p.original_accounting_approval_id;
 IF NOT FOUND OR d.state<>'APPROVED'
    OR d.organization_id<>p.organization_id
    OR d.subject_type<>'FINANCE_SUPPLIER_ACCOUNTING_REVIEW'
    OR d.subject_id<>p.payable_candidate_id
    OR d.subject_version<>p.invoice_review_source_version THEN
   RAISE EXCEPTION 'Actual supplier documentation review must be approved'
     USING ERRCODE='23514';
 END IF;
 SELECT * INTO r FROM approval_request WHERE id=NEW.journal_posting_approval_id;
 IF NOT FOUND OR r.state<>'APPROVED' OR
    r.organization_id<>p.organization_id OR
    r.subject_type<>'FINANCE_AP_JOURNAL_POSTING' OR
    r.subject_id<>p.id OR
    r.subject_version<>p.ledger_subject_version OR
    r.policy_key<>p.posting_policy_key THEN
   RAISE EXCEPTION 'Independent exact journal posting approval required'
     USING ERRCODE='23514';
 END IF;
 IF r.requester_user_id=NEW.posted_by_user_id OR
    p.created_by_user_id=NEW.posted_by_user_id OR
    NOT EXISTS (
      SELECT 1 FROM approval_decision decision_record
      WHERE decision_record.approval_request_id=r.id
        AND decision_record.decision='APPROVE'
        AND decision_record.subject_version=r.subject_version
        AND decision_record.actor_user_id<>p.created_by_user_id
        AND decision_record.actor_user_id<>NEW.posted_by_user_id
    ) THEN
   RAISE EXCEPTION 'Independent real reviewer approval and posting operator required'
     USING ERRCODE='23514';
 END IF;
 SELECT * INTO posting_period FROM finance_gl_period
   WHERE id=p.fiscal_period_id AND organization_id=p.organization_id;
 IF NOT FOUND OR posting_period.state<>'OPEN' OR
    NEW.posted_at::date NOT BETWEEN posting_period.date_start
                              AND posting_period.date_end THEN
   RAISE EXCEPTION 'Posting outside approved open Finance period'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_ap_posted_guard
BEFORE INSERT ON finance_ap_posted_journal FOR EACH ROW
EXECUTE FUNCTION finance_ap_posted_guard();

CREATE FUNCTION finance_ap_posted_line_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE j finance_ap_posted_journal%ROWTYPE; p finance_ap_posting_proposal_line%ROWTYPE;
BEGIN
 SELECT * INTO j FROM finance_ap_posted_journal WHERE id=NEW.journal_id;
 SELECT * INTO p FROM finance_ap_posting_proposal_line WHERE id=NEW.proposal_line_id;
 IF NOT FOUND OR j.id IS NULL OR NEW.organization_id<>j.organization_id OR
    p.proposal_id<>j.proposal_id OR p.organization_id<>NEW.organization_id OR
    p.line_number<>NEW.line_number OR p.gl_account_id<>NEW.gl_account_id
    OR p.side<>NEW.side OR p.amount<>NEW.amount THEN
   RAISE EXCEPTION 'Posted journal line must exactly copy approved proposal line'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_ap_posted_line_guard
BEFORE INSERT ON finance_ap_posted_journal_line FOR EACH ROW
EXECUTE FUNCTION finance_ap_posted_line_guard();

CREATE FUNCTION finance_ap_posted_complete_guard()
RETURNS trigger LANGUAGE plpgsql AS $
DECLARE
 j finance_ap_posted_journal%ROWTYPE;
 totals record;
 posting_id uuid;
BEGIN
 -- NEW.journal_id does not exist on the journal-header trigger record.
 IF TG_TABLE_NAME='finance_ap_posted_journal' THEN
   posting_id := NEW.id;
 ELSE
   posting_id := NEW.journal_id;
 END IF;
 SELECT * INTO j FROM finance_ap_posted_journal WHERE id=posting_id;
 IF NOT FOUND THEN RETURN NULL; END IF;
 SELECT COUNT(*) AS n,
   COALESCE(SUM(l.amount) FILTER(WHERE l.side='DEBIT'),0) AS dr,
   COALESCE(SUM(l.amount) FILTER(WHERE l.side='CREDIT'),0) AS cr
 INTO totals FROM finance_ap_posted_journal_line l
 WHERE l.journal_id=j.id;
 IF totals.n<>(SELECT COUNT(*) FROM finance_ap_posting_proposal_line p
                WHERE p.proposal_id=j.proposal_id) OR
    totals.dr<>totals.cr OR totals.cr<>j.gross_amount THEN
   RAISE EXCEPTION 'Posted accounting journal must copy complete balanced proposal'
     USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER finance_ap_posted_complete_journal
AFTER INSERT ON finance_ap_posted_journal
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION finance_ap_posted_complete_guard();
CREATE CONSTRAINT TRIGGER finance_ap_posted_complete_line
AFTER INSERT ON finance_ap_posted_journal_line
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION finance_ap_posted_complete_guard();

-- No company chart/period/policy bindings, data or financial actions seeded.
