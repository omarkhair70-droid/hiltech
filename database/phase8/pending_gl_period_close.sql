-- Phase8L UNNUMBERED: append-only company accounting period closure.
-- This extends database/phase8/pending_ap_ledger_posting.sql, never installed
-- automatically. DevOps assigns Flyway ordering after merged financial schema.
-- A closed accounting period cannot accept later original supplier AP journals.
-- NO default accounts, approvals, company chart or financial period is seeded.

CREATE VIEW finance_gl_period_close_source AS
SELECT p.id AS period_id,p.organization_id,p.date_start,p.date_end,p.state,
       COUNT(j.id)::bigint AS journal_count,
       encode(sha256(convert_to(
          concat_ws('|',
            'FINANCE_GL_PERIOD_CLOSE_SOURCE_V1',p.id::text,
            p.organization_id::text,p.date_start::text,p.date_end::text,
            p.state,COUNT(j.id)::text,
            COALESCE(string_agg(
              concat_ws(':',j.id::text,j.payable_candidate_id::text,
                j.gross_amount::text,j.currency,j.ledger_fingerprint_sha256),
              '|' ORDER BY j.id
            ),'EMPTY')
          ),'UTF8')),'hex') AS snapshot_sha256
FROM finance_gl_period p
LEFT JOIN finance_ap_posted_journal j
 ON j.fiscal_period_id=p.id AND j.organization_id=p.organization_id
GROUP BY p.id,p.organization_id,p.date_start,p.date_end,p.state;

CREATE TABLE finance_gl_period_close (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    period_id uuid NOT NULL,
    approval_request_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
    policy_key varchar(80) NOT NULL CHECK (policy_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    subject_version bigint NOT NULL CHECK (subject_version>0),
    snapshot_sha256 char(64) NOT NULL CHECK(snapshot_sha256 ~ '^[a-f0-9]{64}$'),
    journal_count bigint NOT NULL CHECK(journal_count>=0),
    closed_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    closed_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
    FOREIGN KEY(period_id,organization_id)
        REFERENCES finance_gl_period(id,organization_id) ON DELETE RESTRICT,
    UNIQUE(organization_id,period_id),
    UNIQUE(organization_id,id)
);

CREATE FUNCTION finance_gl_close_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
 p finance_gl_period%ROWTYPE;
 s finance_gl_period_close_source%ROWTYPE;
 a approval_request%ROWTYPE;
BEGIN
  -- All Finance journal insertions take this exact period lock.
  SELECT * INTO p FROM finance_gl_period
    WHERE id=NEW.period_id AND organization_id=NEW.organization_id
    FOR UPDATE;
  IF NOT FOUND OR p.state<>'OPEN'
    OR NEW.closed_at::date<=p.date_end
    OR EXISTS(SELECT 1 FROM finance_gl_period_close
         WHERE period_id=NEW.period_id AND organization_id=NEW.organization_id) THEN
      RAISE EXCEPTION 'Finance period missing or already closed'
        USING ERRCODE='23514';
  END IF;
  SELECT * INTO s FROM finance_gl_period_close_source
    WHERE period_id=NEW.period_id AND organization_id=NEW.organization_id;
  IF NOT FOUND OR s.snapshot_sha256<>NEW.snapshot_sha256
    OR s.journal_count<>NEW.journal_count OR
    EXISTS(
      SELECT 1 FROM finance_ap_posted_journal j
      WHERE j.organization_id=NEW.organization_id
        AND j.fiscal_period_id=NEW.period_id
        AND j.posted_at>NEW.closed_at
    ) THEN
      RAISE EXCEPTION 'New Finance postings changed the period close snapshot'
        USING ERRCODE='23514';
  END IF;
  SELECT * INTO a FROM approval_request WHERE id=NEW.approval_request_id;
  IF NOT FOUND OR a.state<>'APPROVED'
    OR a.organization_id<>NEW.organization_id
    OR a.subject_type<>'FINANCE_GL_PERIOD_CLOSE'
    OR a.subject_id<>NEW.period_id
    OR a.subject_version<>NEW.subject_version
    OR a.policy_key<>NEW.policy_key
    OR a.requester_user_id=NEW.closed_by_user_id
    OR a.completed_at IS NULL OR a.completed_at>NEW.closed_at
    OR NOT EXISTS (
      SELECT 1 FROM approval_policy_version policy
      WHERE policy.organization_id=NEW.organization_id
        AND policy.policy_key=NEW.policy_key
        AND policy.version_number=a.policy_version
        AND policy.lifecycle_state='ACTIVE'
        AND policy.approval_required=true
        AND policy.step_mode='SINGLE'
        AND policy.effective_from<=NEW.closed_at
        AND (policy.effective_to IS NULL
             OR policy.effective_to>NEW.closed_at)
    )
    OR NOT EXISTS (
      SELECT 1 FROM approval_decision d
      WHERE d.approval_request_id=a.id
        AND d.subject_version=a.subject_version
        AND d.decision='APPROVE'
        AND d.decided_at<=NEW.closed_at
        AND d.actor_user_id<>a.requester_user_id
        AND d.actor_user_id<>NEW.closed_by_user_id
    ) THEN
      RAISE EXCEPTION 'Independent actual period closure approval required'
        USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END; $$;

CREATE TRIGGER finance_gl_close_guard
BEFORE INSERT ON finance_gl_period_close FOR EACH ROW
EXECUTE FUNCTION finance_gl_close_guard();

CREATE TRIGGER finance_gl_close_immutable
BEFORE UPDATE OR DELETE ON finance_gl_period_close FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

-- This is a second DB-level guard. Source-to-ledger application checks are
-- insufficient because a direct SQL insert could race with period closure.
CREATE FUNCTION finance_ap_reject_closed_period()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_gl_period%ROWTYPE;
BEGIN
  SELECT * INTO p FROM finance_gl_period
    WHERE id=NEW.fiscal_period_id AND organization_id=NEW.organization_id
    FOR UPDATE;
  IF NOT FOUND OR p.state<>'OPEN' OR
     EXISTS(
       SELECT 1 FROM finance_gl_period_close
       WHERE organization_id=NEW.organization_id
         AND period_id=NEW.fiscal_period_id
     ) THEN
    RAISE EXCEPTION 'Supplier payable journal cannot post to closed period'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END; $$;

CREATE TRIGGER finance_aa_reject_closed_period
BEFORE INSERT ON finance_ap_posted_journal FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_closed_period();

-- A closure event does not mutate historic GL period rows. It is the
-- authoritative effective closed state, including for stale UI/cache paths.
