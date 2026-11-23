-- Phase 8N Finance AP reversal: UNNUMBERED / DRAFT / DEFAULT OFF.
-- Append-only approved accounting adjustment, never bank money movement.

-- Unnumbered, not installed by Flyway until approved by DevOps.
-- A reviewed proposal binds target period, exact posted source, amount,
-- reason and approval source version. Historical records stay immutable.
CREATE TABLE finance_ap_reversal_proposal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 original_journal_id uuid NOT NULL,
 target_period_id uuid NOT NULL,
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 gross_amount numeric(24,6) NOT NULL CHECK(gross_amount > 0),
 source_ledger_fingerprint_sha256 char(64) NOT NULL
   CHECK(source_ledger_fingerprint_sha256 ~ '^[a-f0-9]{64}$'),
 approval_subject_version bigint NOT NULL CHECK(approval_subject_version > 0),
 approval_policy_key varchar(80) NOT NULL CHECK(approval_policy_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
 reason_reference varchar(180) NOT NULL CHECK(length(btrim(reason_reference)) >= 8),
 prepared_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,original_journal_id),
 FOREIGN KEY(original_journal_id,organization_id)
   REFERENCES finance_ap_posted_journal(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(target_period_id,organization_id)
   REFERENCES finance_gl_period(id,organization_id) ON DELETE RESTRICT
);
CREATE TRIGGER finance_ap_reversal_proposal_immutable
BEFORE UPDATE OR DELETE ON finance_ap_reversal_proposal FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE TABLE finance_ap_journal_reversal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 original_journal_id uuid NOT NULL,
 proposal_id uuid NOT NULL,
 target_period_id uuid NOT NULL,
 approval_request_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
 approval_subject_version bigint NOT NULL CHECK (approval_subject_version > 0),
 approval_policy_key varchar(80) NOT NULL CHECK (approval_policy_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
 source_ledger_fingerprint_sha256 char(64) NOT NULL CHECK (source_ledger_fingerprint_sha256 ~ '^[a-f0-9]{64}$'),
 currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
 gross_amount numeric(24,6) NOT NULL CHECK (gross_amount > 0),
 reason_reference varchar(180) NOT NULL CHECK (length(btrim(reason_reference)) >= 8),
 posted_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 posted_at timestamptz NOT NULL,
 correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
 UNIQUE(organization_id,id),
 UNIQUE(original_journal_id),
 FOREIGN KEY(original_journal_id,organization_id)
   REFERENCES finance_ap_posted_journal(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(proposal_id,organization_id)
   REFERENCES finance_ap_reversal_proposal(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(target_period_id,organization_id)
   REFERENCES finance_gl_period(id,organization_id) ON DELETE RESTRICT
);
CREATE TABLE finance_ap_journal_reversal_line (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL,
 reversal_id uuid NOT NULL,
 original_line_id uuid NOT NULL REFERENCES finance_ap_posted_journal_line(id) ON DELETE RESTRICT,
 line_number integer NOT NULL CHECK (line_number BETWEEN 1 AND 32),
 gl_account_id uuid NOT NULL,
 side varchar(8) NOT NULL CHECK (side IN ('DEBIT','CREDIT')),
 amount numeric(24,6) NOT NULL CHECK (amount > 0),
 UNIQUE(reversal_id,original_line_id),
 UNIQUE(reversal_id,line_number),
 FOREIGN KEY(reversal_id,organization_id)
   REFERENCES finance_ap_journal_reversal(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(gl_account_id,organization_id)
   REFERENCES finance_gl_account(id,organization_id) ON DELETE RESTRICT
);

CREATE FUNCTION finance_ap_reversal_insert_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE j finance_ap_posted_journal%ROWTYPE;
        p finance_gl_period%ROWTYPE;
        a approval_request%ROWTYPE;
        src_proposal finance_ap_posting_proposal%ROWTYPE;
        exact_proposal finance_ap_reversal_proposal%ROWTYPE;
BEGIN
  SELECT * INTO j FROM finance_ap_posted_journal
    WHERE id=NEW.original_journal_id AND organization_id=NEW.organization_id;
  IF NOT FOUND OR j.currency<>NEW.currency OR j.gross_amount<>NEW.gross_amount
     OR j.ledger_fingerprint_sha256<>NEW.source_ledger_fingerprint_sha256
     OR NEW.posted_at<j.posted_at THEN
     RAISE EXCEPTION 'Exact posted source journal required for reversal' USING ERRCODE='23514';
  END IF;
  SELECT * INTO src_proposal FROM finance_ap_posting_proposal
    WHERE id=j.proposal_id AND organization_id=j.organization_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'Source proposal missing' USING ERRCODE='23514'; END IF;

  SELECT * INTO exact_proposal FROM finance_ap_reversal_proposal
    WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id;
  IF NOT FOUND OR exact_proposal.original_journal_id<>NEW.original_journal_id
    OR exact_proposal.target_period_id<>NEW.target_period_id
    OR exact_proposal.source_ledger_fingerprint_sha256<>NEW.source_ledger_fingerprint_sha256
    OR exact_proposal.currency<>NEW.currency
    OR exact_proposal.gross_amount<>NEW.gross_amount
    OR exact_proposal.approval_policy_key<>NEW.approval_policy_key
    OR exact_proposal.approval_subject_version<>NEW.approval_subject_version
    OR exact_proposal.reason_reference<>NEW.reason_reference
    OR exact_proposal.prepared_by_user_id=NEW.posted_by_user_id THEN
      RAISE EXCEPTION 'Reversal must match immutable reviewed proposal' USING ERRCODE='23514';
  END IF;
  -- If Phase 8U bank allocation is installed, do not erase a supplier
  -- liability while an effective bank payment still points to it.
  -- A prior independently approved cash-match correction must first void
  -- the erroneous match; cash itself is never automatically refunded.
  IF to_regclass('public.finance_cash_allocation_effective') IS NOT NULL THEN
    IF EXISTS(
      SELECT 1 FROM finance_cash_allocation_effective ca
      WHERE ca.organization_id=NEW.organization_id
        AND ca.source_kind='AP'
        AND ca.source_journal_id=NEW.original_journal_id
    ) THEN
      RAISE EXCEPTION 'Cash-linked supplier AP cannot be reversed before approved cash correction'
        USING ERRCODE='23514';
    END IF;
  END IF;
  -- A supplier AP with a signed physical cash payment cannot be reversed
  -- as unpaid. A separate cash accounting/period adjustment is required.
  IF to_regclass('public.finance_cash_voucher_record') IS NOT NULL THEN
    IF EXISTS(
      SELECT 1 FROM finance_cash_voucher_record c
      WHERE c.organization_id=NEW.organization_id
        AND c.source_kind='AP'
        AND c.source_journal_id=NEW.original_journal_id
        AND c.direction='PAYMENT'
    ) THEN
       RAISE EXCEPTION 'Physical supplier cash payment blocks full AP reversal'
         USING ERRCODE='23514';
    END IF;
  END IF;
  -- Same lock order as original AP posting and period closure.
  SELECT * INTO p FROM finance_gl_period
    WHERE id=NEW.target_period_id AND organization_id=NEW.organization_id FOR UPDATE;
  IF NOT FOUND OR p.state<>'OPEN'
    OR (NEW.posted_at AT TIME ZONE 'UTC')::date NOT BETWEEN p.date_start AND p.date_end
    OR EXISTS(SELECT 1 FROM finance_gl_period_close c
      WHERE c.organization_id=NEW.organization_id AND c.period_id=p.id) THEN
    RAISE EXCEPTION 'Reversal target must be currently open Finance period' USING ERRCODE='23514';
  END IF;
  IF EXISTS(SELECT 1 FROM finance_gl_period_close c
      WHERE c.organization_id=NEW.organization_id AND c.period_id=j.fiscal_period_id)
     AND p.date_start <= (j.posted_at AT TIME ZONE 'UTC')::date THEN
    RAISE EXCEPTION 'Closed source requires later period adjustment' USING ERRCODE='23514';
  END IF;

  SELECT * INTO a FROM approval_request
    WHERE id=NEW.approval_request_id AND organization_id=NEW.organization_id;
  IF NOT FOUND OR a.state<>'APPROVED'
    OR a.subject_type<>'FINANCE_AP_JOURNAL_REVERSAL'
    OR a.subject_id<>exact_proposal.id
    OR a.subject_version<>NEW.approval_subject_version
    OR a.policy_key<>NEW.approval_policy_key
    OR a.requester_user_id=NEW.posted_by_user_id
    OR a.completed_at IS NULL OR a.completed_at>NEW.posted_at
    OR src_proposal.created_by_user_id=NEW.posted_by_user_id
    OR a.requester_user_id<>exact_proposal.prepared_by_user_id
    OR NOT EXISTS (
      SELECT 1 FROM approval_decision d WHERE d.approval_request_id=a.id
        AND d.subject_version=a.subject_version AND d.decision='APPROVE'
        AND d.actor_user_id<>a.requester_user_id
        AND d.actor_user_id<>NEW.posted_by_user_id
        AND d.actor_user_id<>src_proposal.created_by_user_id
        AND d.actor_user_id<>exact_proposal.prepared_by_user_id
    )
    OR NOT EXISTS(
      SELECT 1 FROM approval_policy_version v
      WHERE v.organization_id=NEW.organization_id
        AND v.policy_key=NEW.approval_policy_key
        AND v.version_number=a.policy_version
        AND v.lifecycle_state='ACTIVE'
        AND v.approval_required=true AND v.step_mode='SINGLE'
        AND v.effective_from<=NEW.posted_at
        AND (v.effective_to IS NULL OR v.effective_to>NEW.posted_at)
    ) THEN
    RAISE EXCEPTION 'Independent original-bound reversal approval required' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER finance_ap_reversal_insert_guard
BEFORE INSERT ON finance_ap_journal_reversal FOR EACH ROW
EXECUTE FUNCTION finance_ap_reversal_insert_guard();

CREATE FUNCTION finance_ap_reversal_line_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r finance_ap_journal_reversal%ROWTYPE;
        l finance_ap_posted_journal_line%ROWTYPE;
BEGIN
 SELECT * INTO r FROM finance_ap_journal_reversal WHERE id=NEW.reversal_id
   AND organization_id=NEW.organization_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'Reversal missing' USING ERRCODE='23514'; END IF;
 SELECT * INTO l FROM finance_ap_posted_journal_line WHERE id=NEW.original_line_id
   AND journal_id=r.original_journal_id AND organization_id=r.organization_id;
 IF NOT FOUND OR NEW.gl_account_id<>l.gl_account_id OR
   NEW.line_number<>l.line_number OR NEW.amount<>l.amount OR
   NEW.side<>(CASE WHEN l.side='DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END) THEN
   RAISE EXCEPTION 'Reversal line must mirror opposite side of original' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_ap_reversal_line_guard
BEFORE INSERT ON finance_ap_journal_reversal_line FOR EACH ROW
EXECUTE FUNCTION finance_ap_reversal_line_guard();

CREATE FUNCTION finance_ap_reversal_complete_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r finance_ap_journal_reversal%ROWTYPE;
        n bigint; dr numeric(24,6); cr numeric(24,6); original_count bigint;
        reversal_id uuid;
BEGIN
 IF TG_TABLE_NAME='finance_ap_journal_reversal' THEN reversal_id:=NEW.id;
 ELSE reversal_id:=NEW.reversal_id; END IF;
 SELECT * INTO r FROM finance_ap_journal_reversal WHERE id=reversal_id;
 IF NOT FOUND THEN RETURN NULL; END IF;
 SELECT count(*),coalesce(sum(amount) FILTER(WHERE side='DEBIT'),0),
   coalesce(sum(amount) FILTER(WHERE side='CREDIT'),0)
 INTO n,dr,cr FROM finance_ap_journal_reversal_line WHERE reversal_id=r.id;
 SELECT count(*) INTO original_count FROM finance_ap_posted_journal_line
   WHERE journal_id=r.original_journal_id;
 IF n<>original_count OR n NOT BETWEEN 2 AND 32
   OR dr<>cr OR dr<>r.gross_amount THEN
   RAISE EXCEPTION 'Incomplete or unbalanced immutable reversal' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER finance_ap_reversal_complete_header
AFTER INSERT ON finance_ap_journal_reversal
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION finance_ap_reversal_complete_guard();
CREATE CONSTRAINT TRIGGER finance_ap_reversal_complete_line
AFTER INSERT ON finance_ap_journal_reversal_line
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION finance_ap_reversal_complete_guard();

CREATE TRIGGER finance_ap_reversal_header_immutable
BEFORE UPDATE OR DELETE ON finance_ap_journal_reversal FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();
CREATE TRIGGER finance_ap_reversal_line_immutable
BEFORE UPDATE OR DELETE ON finance_ap_journal_reversal_line FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

-- P8L fiscal close snapshot currently aggregates only original AP events.
-- Do not deploy reversal posting until close source and its approved snapshot
-- include reversal events consistently, with tests for concurrent close/post.

-- Extend P8L's source-truthful fiscal snapshot to include reversal events.
-- Replacing the existing view preserves its schema but changes the SHA-256
-- whenever either original postings OR append-only reversals change.
CREATE OR REPLACE VIEW finance_gl_period_close_source AS
SELECT p.id AS period_id,p.organization_id,p.date_start,p.date_end,p.state,
       events.journal_count,
       encode(sha256(convert_to(
         concat_ws('|','FINANCE_GL_PERIOD_CLOSE_SOURCE_V2',
           p.id::text,p.organization_id::text,p.date_start::text,
           p.date_end::text,p.state,events.journal_count::text,
           coalesce(events.event_fingerprints,'EMPTY')
         ),'UTF8')),'hex') AS snapshot_sha256
FROM finance_gl_period p
CROSS JOIN LATERAL (
    SELECT count(*)::bigint AS journal_count,
           string_agg(e.fingerprint,'|' ORDER BY e.fingerprint) AS event_fingerprints
    FROM (
      SELECT concat_ws(':','AP',j.id::text,
               j.payable_candidate_id::text,j.gross_amount::text,
               j.currency,j.ledger_fingerprint_sha256) AS fingerprint
      FROM finance_ap_posted_journal j
      WHERE j.organization_id=p.organization_id AND j.fiscal_period_id=p.id
      UNION ALL
      SELECT concat_ws(':','REVERSAL',r.id::text,
               r.original_journal_id::text,r.gross_amount::text,
               r.currency,r.source_ledger_fingerprint_sha256)
      FROM finance_ap_journal_reversal r
      WHERE r.organization_id=p.organization_id AND r.target_period_id=p.id
    ) e
) events;
