CREATE TABLE finance_payroll_authority_binding (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 identity_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 authority_key varchar(16) NOT NULL CHECK(authority_key IN('PREPARE','REVIEW')),
 active boolean NOT NULL DEFAULT true,
 effective_from timestamptz NOT NULL,
 effective_to timestamptz,
 created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 version bigint NOT NULL DEFAULT 1 CHECK(version>=1),
 CHECK(effective_to IS NULL OR effective_to>=effective_from)
);
CREATE UNIQUE INDEX uq_finance_payroll_authority_current
 ON finance_payroll_authority_binding(organization_id,identity_id,authority_key) WHERE active;

CREATE FUNCTION finance_payroll_current_authority(org uuid, actor uuid, role_key varchar)
RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM finance_payroll_authority_binding b
  JOIN organization o ON o.id=b.organization_id JOIN user_identity u ON u.id=b.identity_id
  WHERE b.organization_id=org AND b.identity_id=actor AND b.authority_key=role_key
   AND b.active AND b.effective_from<=statement_timestamp() AND (b.effective_to IS NULL OR b.effective_to>statement_timestamp())
   AND o.organization_type='HILTECH' AND o.status='ACTIVE' AND u.status='ACTIVE'
   AND EXISTS(SELECT 1 FROM organization_membership m WHERE m.organization_id=org AND m.user_identity_id=actor
    AND m.state='ACTIVE' AND m.valid_from<=statement_timestamp() AND (m.valid_until IS NULL OR m.valid_until>statement_timestamp())))
$$;

CREATE TABLE finance_payroll_payment_batch (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 review_batch_id uuid NOT NULL REFERENCES finance_payroll_review_batch(id) ON DELETE RESTRICT,
 run_version_id uuid NOT NULL,
 prepared_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 prepared_at timestamptz NOT NULL,
 UNIQUE(organization_id,run_version_id),
 UNIQUE(organization_id,id),
 FOREIGN KEY(run_version_id,organization_id) REFERENCES payroll_run_version(id,organization_id) ON DELETE RESTRICT
);
CREATE TABLE finance_payroll_instruction (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL,
 batch_id uuid NOT NULL,
 run_version_id uuid NOT NULL,
 employee_id uuid NOT NULL REFERENCES employee(id) ON DELETE RESTRICT,
 amount numeric(24,6) NOT NULL CHECK(amount>0),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 source_fingerprint char(64) NOT NULL,
 version bigint NOT NULL DEFAULT 1 CHECK(version=1),
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,run_version_id,employee_id),
 FOREIGN KEY(batch_id,organization_id) REFERENCES finance_payroll_payment_batch(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(run_version_id,organization_id) REFERENCES payroll_run_version(id,organization_id) ON DELETE RESTRICT
);
CREATE FUNCTION finance_payroll_batch_complete_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b finance_payroll_payment_batch%ROWTYPE;
        batch uuid;
BEGIN
 IF TG_TABLE_NAME='finance_payroll_payment_batch' THEN batch:=NEW.id; ELSE batch:=NEW.batch_id; END IF;
 SELECT * INTO b FROM finance_payroll_payment_batch WHERE id=batch;
 IF b.id IS NULL OR NOT finance_payroll_current_authority(b.organization_id,b.prepared_by_user_id,'PREPARE') OR NOT EXISTS (
   SELECT 1 FROM finance_payroll_review_batch r JOIN payroll_run_version v ON v.id=r.run_version_id
   JOIN payroll_run_release p ON p.id=r.release_id AND p.run_version_id=v.id
   WHERE r.id=b.review_batch_id AND r.organization_id=b.organization_id AND r.run_version_id=b.run_version_id
     AND v.organization_id=b.organization_id AND r.run_source_fingerprint=v.source_fingerprint
     AND p.run_source_fingerprint=v.source_fingerprint AND r.payable_total=v.net AND r.currency=v.currency
 ) OR EXISTS (
   SELECT 1 FROM payroll_run_correction c WHERE c.replacement_run_version_id=b.run_version_id
     AND c.finance_disposition='SETTLED_DIFFERENTIAL_AUTHORIZED'
 ) OR (SELECT count(*) FROM finance_payroll_instruction i WHERE i.batch_id=batch) <>
      (SELECT count(*) FROM payroll_run_employee_line l WHERE l.run_version_id=b.run_version_id AND l.net>0)
 OR EXISTS (
   SELECT 1 FROM finance_payroll_instruction i LEFT JOIN payroll_run_employee_line l
     ON l.run_version_id=b.run_version_id AND l.employee_id=i.employee_id
   JOIN payroll_run_version v ON v.id=b.run_version_id
   WHERE i.batch_id=batch AND (i.organization_id<>b.organization_id OR i.run_version_id<>b.run_version_id
     OR l.id IS NULL OR i.amount<>l.net OR i.currency<>l.currency OR i.source_fingerprint<>v.source_fingerprint)
 ) THEN RAISE EXCEPTION 'Payroll instructions must match one complete released payroll source' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER finance_payroll_batch_complete AFTER INSERT ON finance_payroll_payment_batch
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION finance_payroll_batch_complete_guard();
CREATE CONSTRAINT TRIGGER finance_payroll_instruction_complete AFTER INSERT ON finance_payroll_instruction
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION finance_payroll_batch_complete_guard();
CREATE TRIGGER finance_payroll_batch_immutable BEFORE UPDATE OR DELETE ON finance_payroll_payment_batch
 FOR EACH ROW EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();
CREATE TRIGGER finance_payroll_instruction_immutable BEFORE UPDATE OR DELETE ON finance_payroll_instruction
 FOR EACH ROW EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE TABLE finance_payroll_result_proposal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL,
 instruction_id uuid NOT NULL,
 base_result_version bigint NOT NULL CHECK(base_result_version>=0),
 action varchar(16) NOT NULL CHECK(action IN('SUCCESS','RETURNED','VOID_MATCH')),
 bank_statement_line_id uuid,
 corrects_event_id uuid,
 evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
 evidence_sha256 char(64) NOT NULL CHECK(evidence_sha256 ~ '^[a-f0-9]{64}$'),
 reason varchar(500) NOT NULL CHECK(length(btrim(reason))>=8),
 review_version bigint NOT NULL CHECK(review_version>0),
 policy_key varchar(80) NOT NULL CHECK(policy_key='FINANCE_PAYROLL_RESULT_REVIEW'),
 prepared_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 prepared_at timestamptz NOT NULL,
 UNIQUE(organization_id,id),
 FOREIGN KEY(instruction_id,organization_id) REFERENCES finance_payroll_instruction(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(bank_statement_line_id,organization_id) REFERENCES finance_bank_statement_intake_line(id,organization_id) ON DELETE RESTRICT,
 CHECK((action='VOID_MATCH' AND bank_statement_line_id IS NULL AND corrects_event_id IS NOT NULL)
    OR (action<>'VOID_MATCH' AND bank_statement_line_id IS NOT NULL AND corrects_event_id IS NULL))
);
CREATE TABLE finance_payroll_result_event (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL,
 proposal_id uuid NOT NULL,
 instruction_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0),
 action varchar(16) NOT NULL CHECK(action IN('SUCCESS','RETURNED','VOID_MATCH')),
 outcome varchar(16) NOT NULL CHECK(outcome IN('SUCCESS','RETURNED','UNKNOWN')),
 bank_statement_line_id uuid,
 corrects_event_id uuid,
 evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
 evidence_sha256 char(64) NOT NULL,
 amount numeric(24,6) NOT NULL CHECK(amount>0),
 currency char(3) NOT NULL,
 approval_request_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
 recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 recorded_at timestamptz NOT NULL,
 correlation_id varchar(128) NOT NULL,
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,proposal_id),
 UNIQUE(organization_id,instruction_id,version),
 FOREIGN KEY(proposal_id,organization_id) REFERENCES finance_payroll_result_proposal(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(instruction_id,organization_id) REFERENCES finance_payroll_instruction(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(bank_statement_line_id,organization_id) REFERENCES finance_bank_statement_intake_line(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(corrects_event_id,organization_id) REFERENCES finance_payroll_result_event(id,organization_id) ON DELETE RESTRICT
);
ALTER TABLE finance_payroll_result_proposal ADD CONSTRAINT fk_finance_payroll_corrected_event
 FOREIGN KEY(corrects_event_id,organization_id) REFERENCES finance_payroll_result_event(id,organization_id) ON DELETE RESTRICT;
CREATE TRIGGER finance_payroll_proposal_immutable BEFORE UPDATE OR DELETE ON finance_payroll_result_proposal
 FOR EACH ROW EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();
CREATE TRIGGER finance_payroll_result_immutable BEFORE UPDATE OR DELETE ON finance_payroll_result_event
 FOR EACH ROW EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

-- Receipt of protected original bytes, bound to one proposal/version and viewer.
-- It is never a bank grant, reusable download token, or proof of payment itself.
CREATE TABLE finance_payroll_evidence_view (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL,
 proposal_id uuid NOT NULL,
 evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
 evidence_sha256 char(64) NOT NULL,
 review_version bigint NOT NULL CHECK(review_version>0),
 viewer_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 viewed_at timestamptz NOT NULL,
 correlation_id varchar(128) NOT NULL,
 FOREIGN KEY(proposal_id,organization_id) REFERENCES finance_payroll_result_proposal(id,organization_id) ON DELETE RESTRICT
);
CREATE INDEX idx_finance_payroll_evidence_review ON finance_payroll_evidence_view(organization_id,proposal_id,viewer_user_id,review_version);
CREATE TRIGGER finance_payroll_evidence_view_immutable BEFORE UPDATE OR DELETE ON finance_payroll_evidence_view
 FOR EACH ROW EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE VIEW finance_payroll_bank_usage AS
 SELECT e.organization_id,e.bank_statement_line_id,e.amount
 FROM finance_payroll_result_event e
 WHERE e.bank_statement_line_id IS NOT NULL AND NOT EXISTS (
  SELECT 1 FROM finance_payroll_result_event v WHERE v.corrects_event_id=e.id AND v.organization_id=e.organization_id
 );
CREATE VIEW finance_bank_committed_usage AS
 SELECT organization_id,bank_statement_line_id,allocated_amount AS amount FROM finance_cash_allocation_effective
 UNION ALL SELECT organization_id,bank_statement_line_id,amount FROM finance_employee_funding_record
 UNION ALL SELECT organization_id,bank_statement_line_id,amount FROM finance_employee_settlement_record WHERE bank_statement_line_id IS NOT NULL
 UNION ALL SELECT organization_id,bank_statement_line_id,amount FROM finance_expense_reimbursement_record
 UNION ALL SELECT organization_id,bank_statement_line_id,amount FROM finance_payroll_bank_usage;

CREATE FUNCTION finance_payroll_result_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_payroll_result_proposal%ROWTYPE;
        i finance_payroll_instruction%ROWTYPE;
        a approval_request%ROWTYPE;
        previous finance_payroll_result_event%ROWTYPE;
        bank finance_bank_statement_intake_line%ROWTYPE;
BEGIN
 SELECT * INTO p FROM finance_payroll_result_proposal WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id;
 SELECT * INTO i FROM finance_payroll_instruction WHERE id=NEW.instruction_id AND organization_id=NEW.organization_id FOR UPDATE;
 SELECT * INTO previous FROM finance_payroll_result_event WHERE instruction_id=i.id AND organization_id=i.organization_id ORDER BY version DESC LIMIT 1;
 IF p.id IS NULL OR i.id IS NULL OR p.instruction_id<>i.id OR p.prepared_by_user_id=NEW.recorded_by_user_id
   OR NOT finance_payroll_current_authority(NEW.organization_id,p.prepared_by_user_id,'PREPARE')
   OR NOT finance_payroll_current_authority(NEW.organization_id,NEW.recorded_by_user_id,'REVIEW')
   OR EXISTS(SELECT 1 FROM employee employee JOIN user_identity u ON u.person_id=employee.person_id
     WHERE employee.id=i.employee_id AND u.id=NEW.recorded_by_user_id)
   OR NEW.version<>coalesce(previous.version,0)+1 OR p.base_result_version<>coalesce(previous.version,0)
   OR NEW.action<>p.action OR NEW.bank_statement_line_id IS DISTINCT FROM p.bank_statement_line_id
   OR NEW.corrects_event_id IS DISTINCT FROM p.corrects_event_id OR NEW.evidence_id<>p.evidence_id
   OR NEW.evidence_sha256<>p.evidence_sha256 OR NEW.amount<>i.amount OR NEW.currency<>i.currency
 THEN RAISE EXCEPTION 'Payroll result does not match exact independent proposal' USING ERRCODE='23514'; END IF;
 IF (p.action='SUCCESS' AND (coalesce(previous.outcome,'UNKNOWN')='SUCCESS' OR NEW.outcome<>'SUCCESS'))
   OR (p.action='RETURNED' AND (previous.outcome IS DISTINCT FROM 'SUCCESS' OR NEW.outcome<>'RETURNED'))
   OR (p.action='VOID_MATCH' AND (previous.action IS DISTINCT FROM 'SUCCESS' OR previous.id IS DISTINCT FROM p.corrects_event_id OR NEW.outcome<>'UNKNOWN'))
 THEN RAISE EXCEPTION 'Invalid payroll result transition' USING ERRCODE='23514'; END IF;
 IF p.action='SUCCESS' THEN
   PERFORM 1 FROM payroll_period period JOIN payroll_run_version run ON run.payroll_period_id=period.id
     WHERE run.id=i.run_version_id AND run.organization_id=NEW.organization_id FOR UPDATE OF period;
   IF EXISTS(SELECT 1 FROM payroll_run_version current_run JOIN payroll_run_version newer
       ON newer.organization_id=current_run.organization_id AND newer.payroll_period_id=current_run.payroll_period_id
       JOIN payroll_run_release released ON released.run_version_id=newer.id
       WHERE current_run.id=i.run_version_id AND newer.generation>current_run.generation)
   THEN RAISE EXCEPTION 'Superseded payroll cannot acquire a new successful payment match' USING ERRCODE='23514'; END IF;
 END IF;
 IF p.action<>'VOID_MATCH' THEN
   SELECT * INTO bank FROM finance_bank_statement_intake_line WHERE id=p.bank_statement_line_id AND organization_id=NEW.organization_id FOR UPDATE;
   IF bank.id IS NULL OR bank.amount<>i.amount OR bank.currency<>i.currency
     OR bank.flow<>(CASE WHEN p.action='SUCCESS' THEN 'DEBIT' ELSE 'CREDIT' END)
     OR NOT EXISTS (SELECT 1 FROM finance_bank_statement_intake h
       WHERE h.id=bank.intake_id AND h.organization_id=NEW.organization_id
         AND h.statement_evidence_id=p.evidence_id AND h.statement_sha256=p.evidence_sha256)
   THEN RAISE EXCEPTION 'Payroll result must match exact approved bank evidence' USING ERRCODE='23514'; END IF;
 ELSE
   IF previous.evidence_id<>p.evidence_id OR previous.evidence_sha256<>p.evidence_sha256
   THEN RAISE EXCEPTION 'Payroll correction must preserve original proof' USING ERRCODE='23514'; END IF;
   SELECT * INTO bank FROM finance_bank_statement_intake_line
     WHERE id=previous.bank_statement_line_id AND organization_id=NEW.organization_id FOR UPDATE;
 END IF;
 IF bank.id IS NULL OR EXISTS(SELECT 1 FROM finance_bank_reconciliation_record r
   WHERE r.organization_id=NEW.organization_id AND r.intake_id=bank.intake_id)
 THEN RAISE EXCEPTION 'Signed statement cannot receive payroll matches or match corrections' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS (SELECT 1 FROM evidence e JOIN evidence_upload_session u ON u.evidence_id=e.id
   WHERE e.id=p.evidence_id AND e.organization_id=NEW.organization_id AND e.sha256=p.evidence_sha256
     AND e.target_type='FINANCE_BANK_STATEMENT_INTAKE' AND e.storage_state='READY' AND u.state='READY'
     AND e.finalized_at IS NOT NULL AND u.finalized_at IS NOT NULL AND e.object_key_ref IS NOT NULL
     AND e.sha256=u.expected_sha256 AND e.size_bytes=u.expected_size_bytes
     AND e.classification_code='HIGHLY_RESTRICTED' AND e.client_visibility_mode='INTERNAL_ONLY'
     AND e.evidence_type_code='FINANCE_BANK_STATEMENT_INTAKE' AND e.security_scan_class='BANK_CANONICAL_CSV' AND e.content_type='text/csv')
 THEN RAISE EXCEPTION 'Payroll proof must be finalized canonical evidence' USING ERRCODE='23514'; END IF;
 SELECT * INTO a FROM approval_request WHERE id=NEW.approval_request_id AND organization_id=NEW.organization_id;
 IF a.id IS NULL OR a.state<>'APPROVED' OR a.subject_type<>'FINANCE_PAYROLL_RESULT'
   OR a.subject_id<>p.id OR a.subject_version<>p.review_version OR a.policy_key<>p.policy_key
   OR a.requester_user_id<>p.prepared_by_user_id OR a.completed_at IS NULL
   OR NOT EXISTS(SELECT 1 FROM approval_policy_version policy WHERE policy.organization_id=NEW.organization_id
     AND policy.policy_key=p.policy_key AND policy.version_number=a.policy_version
     AND policy.lifecycle_state='ACTIVE' AND policy.approval_required AND policy.step_mode='SINGLE'
     AND policy.authority_key='FINANCE_PAYROLL_RESULT_REVIEWER' AND policy.reason_required
     AND policy.effective_from<=statement_timestamp() AND (policy.effective_to IS NULL OR policy.effective_to>=statement_timestamp()))
   OR NOT EXISTS (SELECT 1 FROM approval_decision d WHERE d.approval_request_id=a.id AND d.decision='APPROVE'
     AND d.subject_version=p.review_version AND d.actor_user_id=NEW.recorded_by_user_id AND d.actor_user_id<>p.prepared_by_user_id)
   OR NOT EXISTS(SELECT 1 FROM finance_payroll_evidence_view viewed WHERE viewed.organization_id=NEW.organization_id
     AND viewed.proposal_id=p.id AND viewed.review_version=p.review_version AND viewed.evidence_id=p.evidence_id
     AND viewed.evidence_sha256=p.evidence_sha256 AND viewed.viewer_user_id=NEW.recorded_by_user_id)
 THEN RAISE EXCEPTION 'Payroll result requires exact independent approval' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_payroll_result_source BEFORE INSERT ON finance_payroll_result_event
 FOR EACH ROW EXECUTE FUNCTION finance_payroll_result_guard();

CREATE OR REPLACE FUNCTION finance_bank_shared_allocation_cap_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE capacity numeric(24,6); used numeric(24,6);
BEGIN
 IF NEW.bank_statement_line_id IS NULL THEN RETURN NEW; END IF;
 SELECT amount INTO capacity FROM finance_bank_statement_intake_line
 WHERE id=NEW.bank_statement_line_id AND organization_id=NEW.organization_id FOR UPDATE;
 SELECT coalesce(sum(amount),0) INTO used FROM finance_bank_committed_usage
 WHERE organization_id=NEW.organization_id AND bank_statement_line_id=NEW.bank_statement_line_id;
 IF capacity IS NULL OR used>capacity THEN
   RAISE EXCEPTION 'Bank movement is double-allocated across Finance sources' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_bank_shared_cap_payroll AFTER INSERT ON finance_payroll_result_event
 FOR EACH ROW EXECUTE FUNCTION finance_bank_shared_allocation_cap_guard();

-- Every Finance workspace reads the same committed capacity, including payroll.
CREATE OR REPLACE VIEW finance_bank_allocation_exception AS
WITH assigned AS (
 SELECT organization_id,bank_statement_line_id,sum(amount) AS applied
 FROM finance_bank_committed_usage GROUP BY organization_id,bank_statement_line_id
)
SELECT b.organization_id,b.intake_id,b.account_id,
       b.id AS bank_line_id,b.external_transaction_id,b.booked_on,
       b.flow,b.currency,b.amount AS statement_amount,
       coalesce(a.applied,0::numeric) AS allocated_amount,
       b.amount-coalesce(a.applied,0::numeric) AS unallocated_amount,
       CASE WHEN a.applied IS NULL THEN 'UNALLOCATED_BANK_LINE'
            WHEN a.applied=b.amount THEN 'FULLY_ALLOCATED_NOT_RECONCILED'
            WHEN a.applied<b.amount THEN 'PARTIAL_ALLOCATION_EXCEPTION'
            ELSE 'OVERALLOCATED_EXCEPTION'
       END AS exception_state
FROM finance_bank_statement_intake_line b
LEFT JOIN assigned a ON a.organization_id=b.organization_id AND a.bank_statement_line_id=b.id;

-- Bank signoff fingerprints all effective payroll matches alongside prior Finance sources.
CREATE OR REPLACE VIEW finance_bank_reconciliation_snapshot AS
WITH recognized_bank_events AS (
 SELECT organization_id,bank_statement_line_id,id AS event_id,
        source_kind || '_JOURNAL' AS source_kind,
        source_journal_id AS source_id,
        allocated_amount::numeric(24,6) AS amount
 FROM finance_cash_allocation_effective
 UNION ALL
 SELECT organization_id,bank_statement_line_id,id,
        ('FUNDING_' || draft_kind)::varchar AS source_kind,
        employee_draft_id AS source_id,amount::numeric(24,6)
 FROM finance_employee_funding_record
 UNION ALL
 SELECT organization_id,bank_statement_line_id,id,
        ('SETTLEMENT_' || action)::varchar AS source_kind,
        funding_record_id AS source_id,amount::numeric(24,6)
 FROM finance_employee_settlement_record
 WHERE bank_statement_line_id IS NOT NULL
 UNION ALL
 SELECT organization_id,bank_statement_line_id,id,
        'EXPENSE_REIMBURSEMENT' AS source_kind,
        expense_draft_id AS source_id,amount::numeric(24,6)
 FROM finance_expense_reimbursement_record
 UNION ALL
 SELECT e.organization_id,e.bank_statement_line_id,e.id,
        ('PAYROLL_' || e.action)::varchar AS source_kind,
        e.instruction_id AS source_id,e.amount::numeric(24,6)
 FROM finance_payroll_result_event e
 WHERE e.bank_statement_line_id IS NOT NULL AND NOT EXISTS(
   SELECT 1 FROM finance_payroll_result_event correction
   WHERE correction.organization_id=e.organization_id AND correction.corrects_event_id=e.id)
), matching AS (
 SELECT organization_id,bank_statement_line_id,
        sum(amount)::numeric(24,6) AS matched_amount,
        string_agg(concat_ws(':',event_id,source_kind,source_id,amount),
          '+' ORDER BY event_id) AS match_sources
 FROM recognized_bank_events
 GROUP BY organization_id,bank_statement_line_id
)
SELECT h.organization_id,h.id AS intake_id,h.account_id,h.statement_sha256,
       h.statement_lines_sha256,h.currency,h.opening_balance,h.closing_balance,
       h.expected_line_count,
       count(b.id)::bigint AS actual_line_count,
       coalesce(sum(b.amount) FILTER (WHERE b.flow='CREDIT'),0)::numeric(24,6) AS total_credit,
       coalesce(sum(b.amount) FILTER (WHERE b.flow='DEBIT'),0)::numeric(24,6) AS total_debit,
       coalesce(sum(coalesce(m.matched_amount,0)),0)::numeric(24,6) AS total_matched,
       coalesce(bool_and(coalesce(m.matched_amount,0)=b.amount),false) AS fully_matched,
       encode(sha256(convert_to(
         coalesce(string_agg(concat_ws(':',
           b.id,b.external_transaction_id,b.booked_on,b.flow,b.currency,
           b.amount,b.original_line_sha256,coalesce(m.matched_amount,0),
           coalesce(m.match_sources,'NONE')),
           '|' ORDER BY b.id),'EMPTY'),'UTF8')),'hex') AS match_sha256
FROM finance_bank_statement_intake h
JOIN finance_bank_statement_intake_line b
 ON b.organization_id=h.organization_id AND b.intake_id=h.id
LEFT JOIN matching m ON m.organization_id=b.organization_id
 AND m.bank_statement_line_id=b.id
GROUP BY h.organization_id,h.id;
