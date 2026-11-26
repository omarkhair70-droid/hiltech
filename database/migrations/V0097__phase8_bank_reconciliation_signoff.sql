-- Phase 8 bank statement reconciliation sign-off. No transfers or journal postings.
-- A finalized company-imported bank statement may be signed only when EVERY
-- statement line is exactly backed by existing independently approved AP/AR
-- allocations. Employee settlements and non-AP/AR lines remain exceptions
-- until the original Finance scope provides explicit source matching.
-- Historical allocations are append-only, and signature is also append-only.

CREATE VIEW finance_bank_reconciliation_snapshot AS
SELECT h.organization_id,h.id AS intake_id,h.account_id,h.statement_sha256,
       h.statement_lines_sha256,h.currency,h.opening_balance,h.closing_balance,
       h.expected_line_count,
       count(b.id)::bigint AS actual_line_count,
       coalesce(sum(b.amount) FILTER (WHERE b.flow='CREDIT'),0)::numeric(24,6) AS total_credit,
       coalesce(sum(b.amount) FILTER (WHERE b.flow='DEBIT'),0)::numeric(24,6) AS total_debit,
       coalesce(sum(m.matched_amount),0)::numeric(24,6) AS total_matched,
       coalesce(bool_and(m.matched_amount=b.amount),false) AS fully_matched,
       encode(sha256(convert_to(
         coalesce(string_agg(concat_ws(':',
           b.id,b.external_transaction_id,b.booked_on,b.flow,b.currency,
           b.amount,b.original_line_sha256,m.matched_amount,m.match_sources),
           '|' ORDER BY b.id),'EMPTY'),'UTF8')),'hex') AS match_sha256
FROM finance_bank_statement_intake h
JOIN finance_bank_statement_intake_line b
 ON b.organization_id=h.organization_id AND b.intake_id=h.id
LEFT JOIN LATERAL (
 SELECT coalesce(sum(a.allocated_amount),0)::numeric(24,6) AS matched_amount,
        coalesce(string_agg(concat_ws(':',a.id,a.source_kind,
          a.source_journal_id,a.allocated_amount),'+' ORDER BY a.id),'NONE')
          AS match_sources
 FROM finance_cash_allocation_effective a
 WHERE a.organization_id=b.organization_id AND a.bank_statement_line_id=b.id
) m ON true
GROUP BY h.organization_id,h.id;

CREATE TABLE finance_bank_reconciliation_proposal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 intake_id uuid NOT NULL,
 account_id uuid NOT NULL,
 statement_sha256 char(64) NOT NULL CHECK(statement_sha256 ~ '^[a-f0-9]{64}$'),
 match_sha256 char(64) NOT NULL CHECK(match_sha256 ~ '^[a-f0-9]{64}$'),
 expected_line_count bigint NOT NULL CHECK(expected_line_count>0),
 total_credit numeric(24,6) NOT NULL CHECK(total_credit>=0),
 total_debit numeric(24,6) NOT NULL CHECK(total_debit>=0),
 total_matched numeric(24,6) NOT NULL CHECK(total_matched>0),
 reconciliation_reference varchar(180) NOT NULL
   CHECK(length(btrim(reconciliation_reference)) BETWEEN 8 AND 180),
 subject_version bigint NOT NULL CHECK(subject_version>0),
 policy_key varchar(80) NOT NULL CHECK(policy_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
 prepared_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 UNIQUE(organization_id,id), UNIQUE(organization_id,intake_id),
 FOREIGN KEY(intake_id,organization_id)
   REFERENCES finance_bank_statement_intake(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(account_id,organization_id)
   REFERENCES finance_bank_account_register(id,organization_id) ON DELETE RESTRICT
);

-- Writers hold bank movement rows before any effective allocation is changed.
-- Same locking order on approval and finalization ensures observed snapshot
-- is real at the transaction boundary, even with concurrent cash writers.
CREATE FUNCTION finance_bank_reconciliation_lock_lines(target_org uuid,target_intake uuid)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE line_id uuid;
BEGIN
 FOR line_id IN
  SELECT id FROM finance_bank_statement_intake_line
  WHERE organization_id=target_org AND intake_id=target_intake ORDER BY id
 LOOP
  PERFORM 1 FROM finance_bank_statement_intake_line WHERE id=line_id FOR UPDATE;
 END LOOP;
END; $$;

CREATE FUNCTION finance_bank_reconciliation_proposal_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s finance_bank_reconciliation_snapshot%ROWTYPE;
BEGIN
 PERFORM finance_bank_reconciliation_lock_lines(NEW.organization_id,NEW.intake_id);
 SELECT * INTO s FROM finance_bank_reconciliation_snapshot
 WHERE organization_id=NEW.organization_id AND intake_id=NEW.intake_id;
 IF s.intake_id IS NULL OR NOT s.fully_matched
   OR s.actual_line_count<>s.expected_line_count
   OR s.opening_balance+s.total_credit-s.total_debit<>s.closing_balance
   OR s.account_id<>NEW.account_id
   OR s.statement_sha256<>NEW.statement_sha256
   OR s.match_sha256<>NEW.match_sha256
   OR s.actual_line_count<>NEW.expected_line_count
   OR s.total_credit<>NEW.total_credit OR s.total_debit<>NEW.total_debit
   OR s.total_matched<>NEW.total_matched
   OR EXISTS(SELECT 1 FROM finance_bank_reconciliation_record r
     WHERE r.organization_id=NEW.organization_id AND r.intake_id=NEW.intake_id)
 THEN
  RAISE EXCEPTION 'Reconciliation proposal must match fully allocated actual bank ledger'
    USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_bank_reconciliation_proposal_guard
BEFORE INSERT ON finance_bank_reconciliation_proposal FOR EACH ROW
EXECUTE FUNCTION finance_bank_reconciliation_proposal_guard();
CREATE TRIGGER finance_bank_reconciliation_proposal_immutable
BEFORE UPDATE OR DELETE ON finance_bank_reconciliation_proposal FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE TABLE finance_bank_reconciliation_record (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 proposal_id uuid NOT NULL,
 intake_id uuid NOT NULL,
 approval_request_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
 signed_evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
 recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 recorded_at timestamptz NOT NULL,
 correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
 UNIQUE(organization_id,id),UNIQUE(organization_id,intake_id),
 UNIQUE(organization_id,proposal_id),
 FOREIGN KEY(proposal_id,organization_id)
  REFERENCES finance_bank_reconciliation_proposal(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(intake_id,organization_id)
  REFERENCES finance_bank_statement_intake(id,organization_id) ON DELETE RESTRICT
);

CREATE FUNCTION finance_bank_reconciliation_record_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_bank_reconciliation_proposal%ROWTYPE;
        s finance_bank_reconciliation_snapshot%ROWTYPE;
        approval approval_request%ROWTYPE;
BEGIN
 SELECT * INTO p FROM finance_bank_reconciliation_proposal
  WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id;
 IF p.id IS NULL OR p.intake_id<>NEW.intake_id
   OR p.prepared_by_user_id=NEW.recorded_by_user_id
   OR p.created_at>NEW.recorded_at THEN
  RAISE EXCEPTION 'Different authorized operator must sign original reconciliation'
   USING ERRCODE='23514';
 END IF;
 PERFORM finance_bank_reconciliation_lock_lines(NEW.organization_id,p.intake_id);
 SELECT * INTO s FROM finance_bank_reconciliation_snapshot
  WHERE organization_id=NEW.organization_id AND intake_id=p.intake_id;
 IF s.intake_id IS NULL OR NOT s.fully_matched
    OR s.actual_line_count<>s.expected_line_count
    OR s.opening_balance+s.total_credit-s.total_debit<>s.closing_balance
    OR s.account_id<>p.account_id OR s.statement_sha256<>p.statement_sha256
    OR s.match_sha256<>p.match_sha256 OR s.actual_line_count<>p.expected_line_count
    OR s.total_credit<>p.total_credit OR s.total_debit<>p.total_debit
    OR s.total_matched<>p.total_matched THEN
   RAISE EXCEPTION 'Signed reconciliation cannot use changed/unmatched source'
    USING ERRCODE='23514';
 END IF;
 SELECT * INTO approval FROM approval_request
  WHERE id=NEW.approval_request_id AND organization_id=NEW.organization_id;
 IF approval.id IS NULL OR approval.state<>'APPROVED'
   OR approval.subject_type<>'FINANCE_BANK_RECONCILIATION'
   OR approval.subject_id<>p.id OR approval.subject_version<>p.subject_version
   OR approval.policy_key<>p.policy_key
   OR approval.requester_user_id<>p.prepared_by_user_id
   OR approval.requester_user_id=NEW.recorded_by_user_id
   OR approval.completed_at IS NULL OR approval.completed_at>NEW.recorded_at
   OR NOT EXISTS(SELECT 1 FROM approval_decision d
     WHERE d.approval_request_id=approval.id AND d.decision='APPROVE'
       AND d.subject_version=p.subject_version
       AND d.actor_user_id<>p.prepared_by_user_id
       AND d.actor_user_id<>NEW.recorded_by_user_id)
   OR NOT EXISTS(SELECT 1 FROM approval_policy_version v
     WHERE v.organization_id=NEW.organization_id AND v.policy_key=approval.policy_key
       AND v.version_number=approval.policy_version AND v.lifecycle_state='ACTIVE'
       AND v.approval_required=true AND v.step_mode='SINGLE'
       AND v.effective_from<=NEW.recorded_at
       AND (v.effective_to IS NULL OR v.effective_to>NEW.recorded_at))
 THEN
  RAISE EXCEPTION 'Exact independent signed reconciliation approval required'
   USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM evidence e
   JOIN evidence_upload_session u ON u.evidence_id=e.id
   WHERE e.id=NEW.signed_evidence_id AND e.organization_id=NEW.organization_id
     AND e.target_type='FINANCE_BANK_RECONCILIATION'
     AND e.target_id=p.id
     AND e.storage_state='READY' AND u.state='READY'
     AND e.finalized_at IS NOT NULL AND u.finalized_at IS NOT NULL
     AND e.sha256=u.expected_sha256 AND e.size_bytes=u.expected_size_bytes
     AND e.object_key_ref IS NOT NULL AND e.classification_code='HIGHLY_RESTRICTED'
     AND e.client_visibility_mode='INTERNAL_ONLY'
 ) THEN
  RAISE EXCEPTION 'Signed restricted reconciliation document required'
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_bank_reconciliation_record_guard
BEFORE INSERT ON finance_bank_reconciliation_record FOR EACH ROW
EXECUTE FUNCTION finance_bank_reconciliation_record_guard();
CREATE TRIGGER finance_bank_reconciliation_record_immutable
BEFORE UPDATE OR DELETE ON finance_bank_reconciliation_record FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

-- Allocation and correction writers already lock the bank line inside their
-- original guards. The after-insert check cannot race with a signed close:
-- if it commits first the signature snapshot is stale, otherwise it fails.
CREATE FUNCTION finance_bank_reconciliation_prevent_late_match()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE bank_line uuid;
BEGIN
 bank_line:=NEW.bank_statement_line_id;
 IF EXISTS (
  SELECT 1 FROM finance_bank_reconciliation_record r
  JOIN finance_bank_statement_intake_line b
   ON b.organization_id=r.organization_id AND b.intake_id=r.intake_id
  WHERE r.organization_id=NEW.organization_id AND b.id=bank_line
 ) THEN
  RAISE EXCEPTION 'Signed bank statement cannot be reallocated; controlled reopening required'
    USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_bank_reconciliation_no_late_allocation
AFTER INSERT ON finance_cash_allocation_record FOR EACH ROW
EXECUTE FUNCTION finance_bank_reconciliation_prevent_late_match();
CREATE TRIGGER finance_bank_reconciliation_no_late_void
AFTER INSERT ON finance_cash_allocation_void_record FOR EACH ROW
EXECUTE FUNCTION finance_bank_reconciliation_prevent_late_match();

CREATE VIEW finance_bank_reconciliation_status AS
SELECT s.organization_id,s.intake_id,s.account_id,s.currency,
 s.expected_line_count,s.actual_line_count,s.total_credit,s.total_debit,
 s.total_matched,s.fully_matched,s.match_sha256,
 r.id AS reconciliation_record_id,r.approval_request_id,
 r.signed_evidence_id,r.recorded_at,
 (r.id IS NOT NULL AND s.fully_matched) AS internally_reconciled,
 false::boolean AS verified_by_bank_provider
FROM finance_bank_reconciliation_snapshot s
LEFT JOIN finance_bank_reconciliation_record r
  ON r.organization_id=s.organization_id AND r.intake_id=s.intake_id;

-- Internal company signed matching is NOT an authenticated bank callback.
-- No GL entries, journal reversals, bank payment instructions or VAT rules.
