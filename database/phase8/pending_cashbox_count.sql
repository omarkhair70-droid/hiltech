-- Phase 8X: physical cash count witness; unnumbered and feature-gated.
-- This is an append-only observation, NEVER a ledger adjustment or cash transfer.
-- Apply after pending_cashbox_vouchers.sql. DevOps owns canonical Flyway numbering.

CREATE TABLE finance_cash_count_proposal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 cashbox_id uuid NOT NULL,
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 counted_on date NOT NULL,
 counted_amount numeric(24,6) NOT NULL CHECK(counted_amount>=0),
 ledger_amount numeric(24,6) NOT NULL CHECK(ledger_amount>=0),
 ledger_voucher_count bigint NOT NULL CHECK(ledger_voucher_count>=0),
 count_reference varchar(180) NOT NULL CHECK(length(btrim(count_reference)) BETWEEN 8 AND 180),
 subject_version bigint NOT NULL CHECK(subject_version>0),
 policy_key varchar(80) NOT NULL CHECK(policy_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
 prepared_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,cashbox_id,count_reference),
 FOREIGN KEY(cashbox_id,organization_id)
   REFERENCES finance_cashbox_register(id,organization_id) ON DELETE RESTRICT
);

CREATE FUNCTION finance_cash_count_proposal_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b finance_cashbox_register%ROWTYPE;
        balance_amount numeric(24,6);
        balance_count bigint;
BEGIN
 SELECT * INTO b FROM finance_cashbox_register
  WHERE id=NEW.cashbox_id AND organization_id=NEW.organization_id FOR UPDATE;
 IF b.id IS NULL OR b.status<>'ACTIVE'
   OR b.currency<>NEW.currency OR b.custodian_user_id<>NEW.prepared_by_user_id
   OR NEW.counted_on<>(NEW.created_at AT TIME ZONE 'UTC')::date THEN
  RAISE EXCEPTION 'Count requires its active company custodian and exact UTC snapshot'
    USING ERRCODE='23514';
 END IF;
 SELECT cash_on_hand,recorded_vouchers INTO balance_amount,balance_count
  FROM finance_cashbox_balance
  WHERE organization_id=NEW.organization_id AND cashbox_id=NEW.cashbox_id;
 IF balance_amount IS DISTINCT FROM NEW.ledger_amount
    OR balance_count IS DISTINCT FROM NEW.ledger_voucher_count THEN
  RAISE EXCEPTION 'Cash count must preserve current exact book snapshot'
    USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_cash_count_proposal_guard
BEFORE INSERT ON finance_cash_count_proposal FOR EACH ROW
EXECUTE FUNCTION finance_cash_count_proposal_guard();
CREATE TRIGGER finance_cash_count_proposal_immutable
BEFORE UPDATE OR DELETE ON finance_cash_count_proposal FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE TABLE finance_cash_count_record (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 proposal_id uuid NOT NULL,
 approval_request_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
 count_evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
 recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 recorded_at timestamptz NOT NULL,
 correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,proposal_id),
 FOREIGN KEY(proposal_id,organization_id)
  REFERENCES finance_cash_count_proposal(id,organization_id) ON DELETE RESTRICT
);
CREATE FUNCTION finance_cash_count_record_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_cash_count_proposal%ROWTYPE;
        b finance_cashbox_register%ROWTYPE;
        a approval_request%ROWTYPE;
        current_amount numeric(24,6);
        current_count bigint;
BEGIN
 SELECT * INTO p FROM finance_cash_count_proposal
  WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id;
 IF p.id IS NULL OR p.prepared_by_user_id=NEW.recorded_by_user_id
    OR NEW.recorded_at<p.created_at THEN
  RAISE EXCEPTION 'An independent finance recorder must preserve count proposal'
   USING ERRCODE='23514';
 END IF;
 -- Cash voucher writers lock the same row before inserting their events.
 SELECT * INTO b FROM finance_cashbox_register
  WHERE id=p.cashbox_id AND organization_id=NEW.organization_id FOR UPDATE;
 IF b.id IS NULL OR b.status<>'ACTIVE'
   OR b.currency<>p.currency OR b.custodian_user_id<>p.prepared_by_user_id THEN
  RAISE EXCEPTION 'Cashbox custody changed since physical count'
   USING ERRCODE='23514';
 END IF;
 SELECT cash_on_hand,recorded_vouchers INTO current_amount,current_count
  FROM finance_cashbox_balance
  WHERE organization_id=NEW.organization_id AND cashbox_id=p.cashbox_id;
 IF current_amount IS DISTINCT FROM p.ledger_amount
   OR current_count IS DISTINCT FROM p.ledger_voucher_count THEN
  RAISE EXCEPTION 'Cashbox voucher activity invalidated witnessed count'
   USING ERRCODE='23514';
 END IF;
 SELECT * INTO a FROM approval_request
  WHERE id=NEW.approval_request_id AND organization_id=NEW.organization_id;
 IF a.id IS NULL OR a.state<>'APPROVED'
  OR a.subject_type<>'FINANCE_CASHBOX_COUNT'
  OR a.subject_id<>p.id OR a.subject_version<>p.subject_version
  OR a.policy_key<>p.policy_key
  OR a.requester_user_id<>p.prepared_by_user_id
  OR a.completed_at IS NULL OR a.completed_at>NEW.recorded_at
  OR NOT EXISTS (
    SELECT 1 FROM approval_decision d
    WHERE d.approval_request_id=a.id AND d.decision='APPROVE'
      AND d.subject_version=p.subject_version
      AND d.actor_user_id<>p.prepared_by_user_id
      AND d.actor_user_id<>NEW.recorded_by_user_id
  )
  OR NOT EXISTS (
    SELECT 1 FROM approval_policy_version v
    WHERE v.organization_id=NEW.organization_id
      AND v.policy_key=a.policy_key AND v.version_number=a.policy_version
      AND v.lifecycle_state='ACTIVE' AND v.approval_required=true
      AND v.step_mode='SINGLE' AND v.effective_from<=NEW.recorded_at
      AND (v.effective_to IS NULL OR v.effective_to>NEW.recorded_at)
  ) THEN
  RAISE EXCEPTION 'Cash count requires exact independent company approval'
   USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS (
  SELECT 1 FROM evidence e
  JOIN evidence_upload_session u ON u.evidence_id=e.id
  WHERE e.id=NEW.count_evidence_id AND e.organization_id=NEW.organization_id
    AND e.target_type='FINANCE_CASHBOX_COUNT'
    AND e.target_id=p.id AND e.storage_state='READY' AND u.state='READY'
    AND e.finalized_at IS NOT NULL AND u.finalized_at IS NOT NULL
    AND e.sha256=u.expected_sha256 AND e.size_bytes=u.expected_size_bytes
    AND e.object_key_ref IS NOT NULL
    AND e.client_visibility_mode='INTERNAL_ONLY'
 ) THEN
  RAISE EXCEPTION 'Signed, finalized count evidence must belong to this proposal'
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_cash_count_record_guard
BEFORE INSERT ON finance_cash_count_record FOR EACH ROW
EXECUTE FUNCTION finance_cash_count_record_guard();
CREATE TRIGGER finance_cash_count_record_immutable
BEFORE UPDATE OR DELETE ON finance_cash_count_record FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE VIEW finance_cash_count_variance AS
 SELECT r.organization_id,r.id AS count_record_id,
        p.id AS proposal_id,p.cashbox_id,p.counted_on,p.currency,
        p.ledger_amount,p.counted_amount,
        p.counted_amount-p.ledger_amount AS variance_amount,
        p.ledger_voucher_count,r.count_evidence_id,r.approval_request_id,
        r.recorded_at
 FROM finance_cash_count_record r
 JOIN finance_cash_count_proposal p
   ON p.id=r.proposal_id AND p.organization_id=r.organization_id;

-- Deliberately no update to finance_cashbox_balance or posted GL.
-- Cash discrepancies require a future separately approved adjustment contract.
