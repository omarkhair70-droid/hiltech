-- Phase 8P / unnumbered / DEFAULT-OFF. Finance-owned immutable invoice
-- issuance event. Never send the document or post an AR journal automatically.
-- External legal/tax invoice issuance needs company-confirmed sequence,
-- canonical signed invoice evidence and tax approval BEFORE activation.
-- Independent immutable, exact-document issuance proposal. Reviewer must
-- decide on the actual invoice number + PDF evidence, not just an amount.
CREATE TABLE finance_ar_invoice_issuance_proposal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 candidate_id uuid NOT NULL,
 invoice_number varchar(100) NOT NULL CHECK(length(btrim(invoice_number)) BETWEEN 3 AND 100),
 invoice_document_evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
 source_fingerprint_sha256 char(64) NOT NULL CHECK(source_fingerprint_sha256 ~ '^[a-f0-9]{64}
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    candidate_id uuid NOT NULL,
    proposal_id uuid NOT NULL,
    invoice_number varchar(100) NOT NULL
       CHECK(length(btrim(invoice_number)) BETWEEN 3 AND 100),
    invoice_document_evidence_id uuid NOT NULL
       REFERENCES evidence(id) ON DELETE RESTRICT,
    invoice_approval_id uuid NOT NULL
       REFERENCES approval_request(id) ON DELETE RESTRICT,
    approval_policy_key varchar(80) NOT NULL
       CHECK(approval_policy_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    approval_subject_version bigint NOT NULL CHECK(approval_subject_version > 0),
    source_fingerprint_sha256 char(64) NOT NULL
       CHECK(source_fingerprint_sha256 ~ '^[a-f0-9]{64}$'),
    customer_organization_id uuid NOT NULL
       REFERENCES organization(id) ON DELETE RESTRICT,
    currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
    gross_amount numeric(24,6) NOT NULL CHECK(gross_amount > 0),
    issue_date date NOT NULL,
    due_date date NOT NULL,
    issued_by_user_id uuid NOT NULL
       REFERENCES user_identity(id) ON DELETE RESTRICT,
    issued_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL
       CHECK(length(btrim(correlation_id)) > 0),
    UNIQUE(organization_id,id),
    UNIQUE(organization_id,candidate_id),
    UNIQUE(organization_id,invoice_number),
    FOREIGN KEY(candidate_id,organization_id)
       REFERENCES finance_ar_invoice_candidate(id,organization_id) ON DELETE RESTRICT,
    FOREIGN KEY(proposal_id,organization_id)
       REFERENCES finance_ar_invoice_issuance_proposal(id,organization_id) ON DELETE RESTRICT,
    CHECK(due_date >= issue_date),
    CHECK(customer_organization_id <> organization_id)
);

CREATE FUNCTION finance_ar_issued_invoice_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  c finance_ar_invoice_candidate%ROWTYPE;
  a approval_request%ROWTYPE;
  p finance_ar_invoice_issuance_proposal%ROWTYPE;
BEGIN
  SELECT * INTO c FROM finance_ar_invoice_candidate
   WHERE id=NEW.candidate_id AND organization_id=NEW.organization_id
   FOR KEY SHARE;
  IF NOT FOUND OR c.state<>'DRAFT'
     OR c.commercial_source_sha256<>NEW.source_fingerprint_sha256
     OR c.customer_organization_id<>NEW.customer_organization_id
     OR c.currency<>NEW.currency OR c.requested_amount<>NEW.gross_amount
     OR c.invoice_issue_date<>NEW.issue_date
     OR c.invoice_due_date<>NEW.due_date
     OR c.created_by_user_id=NEW.issued_by_user_id THEN
     RAISE EXCEPTION 'Invoice event must exactly match independently prepared Finance candidate'
         USING ERRCODE='23514';
  END IF;

  SELECT * INTO p FROM finance_ar_invoice_issuance_proposal
    WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id;
  IF NOT FOUND OR p.candidate_id<>c.id
     OR p.invoice_number<>NEW.invoice_number
     OR p.invoice_document_evidence_id<>NEW.invoice_document_evidence_id
     OR p.source_fingerprint_sha256<>NEW.source_fingerprint_sha256
     OR p.approval_policy_key<>NEW.approval_policy_key
     OR p.approval_subject_version<>NEW.approval_subject_version
     OR p.created_by_user_id<>c.created_by_user_id THEN
      RAISE EXCEPTION 'Issued invoice does not match reviewed immutable proposal'
        USING ERRCODE='23514';
  END IF;

  SELECT * INTO a FROM approval_request
   WHERE id=NEW.invoice_approval_id AND organization_id=NEW.organization_id;
  IF NOT FOUND OR a.state<>'APPROVED'
      OR a.subject_type<>'FINANCE_AR_INVOICE_ISSUANCE'
      OR a.subject_id<>p.id
      OR a.subject_version<>NEW.approval_subject_version
      OR a.policy_key<>NEW.approval_policy_key
      OR a.requester_user_id<>c.created_by_user_id
      OR a.requester_user_id=NEW.issued_by_user_id
      OR a.completed_at IS NULL OR a.completed_at>NEW.issued_at
      OR NOT EXISTS(
         SELECT 1 FROM approval_policy_version v
         WHERE v.organization_id=NEW.organization_id
           AND v.policy_key=a.policy_key AND v.version_number=a.policy_version
           AND v.lifecycle_state='ACTIVE' AND v.approval_required=true
           AND v.step_mode='SINGLE'
           AND v.effective_from<=NEW.issued_at
           AND (v.effective_to IS NULL OR v.effective_to>NEW.issued_at))
      OR NOT EXISTS(
         SELECT 1 FROM approval_decision d
         WHERE d.approval_request_id=a.id
           AND d.subject_version=a.subject_version AND d.decision='APPROVE'
           AND d.decided_at<=NEW.issued_at
           AND d.actor_user_id<>c.created_by_user_id
           AND d.actor_user_id<>NEW.issued_by_user_id)
  THEN
     RAISE EXCEPTION 'Independent approved exact invoice issuance decision required'
         USING ERRCODE='23514';
  END IF;

  -- A document ID is NOT enough. Verify canonical finalized invoice
  -- evidence belongs to this exact Finance candidate and organization.
  IF NOT EXISTS(
      SELECT 1 FROM evidence e
      JOIN evidence_upload_session u ON u.evidence_id=e.id
      WHERE e.id=NEW.invoice_document_evidence_id
        AND e.organization_id=NEW.organization_id
        AND e.target_type='FINANCE_AR_ISSUED_INVOICE'
        AND e.target_id=NEW.candidate_id
        AND e.storage_state='READY' AND u.state='READY'
        AND e.finalized_at IS NOT NULL AND u.finalized_at IS NOT NULL
        AND e.object_key_ref IS NOT NULL
        AND e.sha256=u.expected_sha256 AND e.size_bytes=u.expected_size_bytes
        AND e.content_type='application/pdf'
        AND e.classification_code='HIGHLY_RESTRICTED'
        AND e.client_visibility_mode='INTERNAL_ONLY')
  THEN
      RAISE EXCEPTION 'Canonical protected invoice PDF evidence required'
        USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END; $$;

CREATE TRIGGER finance_ar_issued_invoice_guard
BEFORE INSERT ON finance_ar_issued_invoice FOR EACH ROW
EXECUTE FUNCTION finance_ar_issued_invoice_guard();
CREATE TRIGGER finance_ar_issued_invoice_immutable
BEFORE UPDATE OR DELETE ON finance_ar_issued_invoice FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

-- This table records internal issuance ONLY when every gate passes.
-- No seeded numbering, taxes, e-invoicing platform submission, delivery,
-- receivable journal, credit note, collections or bank balance.
),
 approval_policy_key varchar(80) NOT NULL CHECK(approval_policy_key ~ '^[A-Z][A-Z0-9_]{2,79}
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    candidate_id uuid NOT NULL,
    invoice_number varchar(100) NOT NULL
       CHECK(length(btrim(invoice_number)) BETWEEN 3 AND 100),
    invoice_document_evidence_id uuid NOT NULL
       REFERENCES evidence(id) ON DELETE RESTRICT,
    invoice_approval_id uuid NOT NULL
       REFERENCES approval_request(id) ON DELETE RESTRICT,
    approval_policy_key varchar(80) NOT NULL
       CHECK(approval_policy_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    approval_subject_version bigint NOT NULL CHECK(approval_subject_version > 0),
    source_fingerprint_sha256 char(64) NOT NULL
       CHECK(source_fingerprint_sha256 ~ '^[a-f0-9]{64}$'),
    customer_organization_id uuid NOT NULL
       REFERENCES organization(id) ON DELETE RESTRICT,
    currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
    gross_amount numeric(24,6) NOT NULL CHECK(gross_amount > 0),
    issue_date date NOT NULL,
    due_date date NOT NULL,
    issued_by_user_id uuid NOT NULL
       REFERENCES user_identity(id) ON DELETE RESTRICT,
    issued_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL
       CHECK(length(btrim(correlation_id)) > 0),
    UNIQUE(organization_id,id),
    UNIQUE(organization_id,candidate_id),
    UNIQUE(organization_id,invoice_number),
    FOREIGN KEY(candidate_id,organization_id)
       REFERENCES finance_ar_invoice_candidate(id,organization_id) ON DELETE RESTRICT,
    CHECK(due_date >= issue_date),
    CHECK(customer_organization_id <> organization_id)
);

CREATE FUNCTION finance_ar_issued_invoice_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  c finance_ar_invoice_candidate%ROWTYPE;
  a approval_request%ROWTYPE;
BEGIN
  SELECT * INTO c FROM finance_ar_invoice_candidate
   WHERE id=NEW.candidate_id AND organization_id=NEW.organization_id
   FOR KEY SHARE;
  IF NOT FOUND OR c.state<>'DRAFT'
     OR c.commercial_source_sha256<>NEW.source_fingerprint_sha256
     OR c.customer_organization_id<>NEW.customer_organization_id
     OR c.currency<>NEW.currency OR c.requested_amount<>NEW.gross_amount
     OR c.invoice_issue_date<>NEW.issue_date
     OR c.invoice_due_date<>NEW.due_date
     OR c.created_by_user_id=NEW.issued_by_user_id THEN
     RAISE EXCEPTION 'Invoice event must exactly match independently prepared Finance candidate'
         USING ERRCODE='23514';
  END IF;

  SELECT * INTO a FROM approval_request
   WHERE id=NEW.invoice_approval_id AND organization_id=NEW.organization_id;
  IF NOT FOUND OR a.state<>'APPROVED'
      OR a.subject_type<>'FINANCE_AR_INVOICE_ISSUANCE'
      OR a.subject_id<>c.id
      OR a.subject_version<>NEW.approval_subject_version
      OR a.policy_key<>NEW.approval_policy_key
      OR a.requester_user_id<>c.created_by_user_id
      OR a.requester_user_id=NEW.issued_by_user_id
      OR a.completed_at IS NULL OR a.completed_at>NEW.issued_at
      OR NOT EXISTS(
         SELECT 1 FROM approval_policy_version v
         WHERE v.organization_id=NEW.organization_id
           AND v.policy_key=a.policy_key AND v.version_number=a.policy_version
           AND v.lifecycle_state='ACTIVE' AND v.approval_required=true
           AND v.step_mode='SINGLE'
           AND v.effective_from<=NEW.issued_at
           AND (v.effective_to IS NULL OR v.effective_to>NEW.issued_at))
      OR NOT EXISTS(
         SELECT 1 FROM approval_decision d
         WHERE d.approval_request_id=a.id
           AND d.subject_version=a.subject_version AND d.decision='APPROVE'
           AND d.decided_at<=NEW.issued_at
           AND d.actor_user_id<>c.created_by_user_id
           AND d.actor_user_id<>NEW.issued_by_user_id)
  THEN
     RAISE EXCEPTION 'Independent approved exact invoice issuance decision required'
         USING ERRCODE='23514';
  END IF;

  -- A document ID is NOT enough. Verify canonical finalized invoice
  -- evidence belongs to this exact Finance candidate and organization.
  IF NOT EXISTS(
      SELECT 1 FROM evidence e
      JOIN evidence_upload_session u ON u.evidence_id=e.id
      WHERE e.id=NEW.invoice_document_evidence_id
        AND e.organization_id=NEW.organization_id
        AND e.target_type='FINANCE_AR_ISSUED_INVOICE'
        AND e.target_id=NEW.candidate_id
        AND e.storage_state='READY' AND u.state='READY'
        AND e.finalized_at IS NOT NULL AND u.finalized_at IS NOT NULL
        AND e.object_key_ref IS NOT NULL
        AND e.sha256=u.expected_sha256 AND e.size_bytes=u.expected_size_bytes
        AND e.content_type='application/pdf'
        AND e.classification_code='HIGHLY_RESTRICTED'
        AND e.client_visibility_mode='INTERNAL_ONLY')
  THEN
      RAISE EXCEPTION 'Canonical protected invoice PDF evidence required'
        USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END; $$;

CREATE TRIGGER finance_ar_issued_invoice_guard
BEFORE INSERT ON finance_ar_issued_invoice FOR EACH ROW
EXECUTE FUNCTION finance_ar_issued_invoice_guard();
CREATE TRIGGER finance_ar_issued_invoice_immutable
BEFORE UPDATE OR DELETE ON finance_ar_issued_invoice FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

-- This table records internal issuance ONLY when every gate passes.
-- No seeded numbering, taxes, e-invoicing platform submission, delivery,
-- receivable journal, credit note, collections or bank balance.
),
 approval_subject_version bigint NOT NULL CHECK(approval_subject_version>0),
 created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,candidate_id),
 UNIQUE(organization_id,invoice_number),
 FOREIGN KEY(candidate_id,organization_id)
    REFERENCES finance_ar_invoice_candidate(id,organization_id) ON DELETE RESTRICT
);
CREATE TRIGGER finance_ar_invoice_issuance_proposal_immutable
BEFORE UPDATE OR DELETE ON finance_ar_invoice_issuance_proposal FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE TABLE finance_ar_issued_invoice (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    candidate_id uuid NOT NULL,
    invoice_number varchar(100) NOT NULL
       CHECK(length(btrim(invoice_number)) BETWEEN 3 AND 100),
    invoice_document_evidence_id uuid NOT NULL
       REFERENCES evidence(id) ON DELETE RESTRICT,
    invoice_approval_id uuid NOT NULL
       REFERENCES approval_request(id) ON DELETE RESTRICT,
    approval_policy_key varchar(80) NOT NULL
       CHECK(approval_policy_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    approval_subject_version bigint NOT NULL CHECK(approval_subject_version > 0),
    source_fingerprint_sha256 char(64) NOT NULL
       CHECK(source_fingerprint_sha256 ~ '^[a-f0-9]{64}$'),
    customer_organization_id uuid NOT NULL
       REFERENCES organization(id) ON DELETE RESTRICT,
    currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
    gross_amount numeric(24,6) NOT NULL CHECK(gross_amount > 0),
    issue_date date NOT NULL,
    due_date date NOT NULL,
    issued_by_user_id uuid NOT NULL
       REFERENCES user_identity(id) ON DELETE RESTRICT,
    issued_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL
       CHECK(length(btrim(correlation_id)) > 0),
    UNIQUE(organization_id,id),
    UNIQUE(organization_id,candidate_id),
    UNIQUE(organization_id,invoice_number),
    FOREIGN KEY(candidate_id,organization_id)
       REFERENCES finance_ar_invoice_candidate(id,organization_id) ON DELETE RESTRICT,
    CHECK(due_date >= issue_date),
    CHECK(customer_organization_id <> organization_id)
);

CREATE FUNCTION finance_ar_issued_invoice_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  c finance_ar_invoice_candidate%ROWTYPE;
  a approval_request%ROWTYPE;
BEGIN
  SELECT * INTO c FROM finance_ar_invoice_candidate
   WHERE id=NEW.candidate_id AND organization_id=NEW.organization_id
   FOR KEY SHARE;
  IF NOT FOUND OR c.state<>'DRAFT'
     OR c.commercial_source_sha256<>NEW.source_fingerprint_sha256
     OR c.customer_organization_id<>NEW.customer_organization_id
     OR c.currency<>NEW.currency OR c.requested_amount<>NEW.gross_amount
     OR c.invoice_issue_date<>NEW.issue_date
     OR c.invoice_due_date<>NEW.due_date
     OR c.created_by_user_id=NEW.issued_by_user_id THEN
     RAISE EXCEPTION 'Invoice event must exactly match independently prepared Finance candidate'
         USING ERRCODE='23514';
  END IF;

  SELECT * INTO a FROM approval_request
   WHERE id=NEW.invoice_approval_id AND organization_id=NEW.organization_id;
  IF NOT FOUND OR a.state<>'APPROVED'
      OR a.subject_type<>'FINANCE_AR_INVOICE_ISSUANCE'
      OR a.subject_id<>c.id
      OR a.subject_version<>NEW.approval_subject_version
      OR a.policy_key<>NEW.approval_policy_key
      OR a.requester_user_id<>c.created_by_user_id
      OR a.requester_user_id=NEW.issued_by_user_id
      OR a.completed_at IS NULL OR a.completed_at>NEW.issued_at
      OR NOT EXISTS(
         SELECT 1 FROM approval_policy_version v
         WHERE v.organization_id=NEW.organization_id
           AND v.policy_key=a.policy_key AND v.version_number=a.policy_version
           AND v.lifecycle_state='ACTIVE' AND v.approval_required=true
           AND v.step_mode='SINGLE'
           AND v.effective_from<=NEW.issued_at
           AND (v.effective_to IS NULL OR v.effective_to>NEW.issued_at))
      OR NOT EXISTS(
         SELECT 1 FROM approval_decision d
         WHERE d.approval_request_id=a.id
           AND d.subject_version=a.subject_version AND d.decision='APPROVE'
           AND d.decided_at<=NEW.issued_at
           AND d.actor_user_id<>c.created_by_user_id
           AND d.actor_user_id<>NEW.issued_by_user_id)
  THEN
     RAISE EXCEPTION 'Independent approved exact invoice issuance decision required'
         USING ERRCODE='23514';
  END IF;

  -- A document ID is NOT enough. Verify canonical finalized invoice
  -- evidence belongs to this exact Finance candidate and organization.
  IF NOT EXISTS(
      SELECT 1 FROM evidence e
      JOIN evidence_upload_session u ON u.evidence_id=e.id
      WHERE e.id=NEW.invoice_document_evidence_id
        AND e.organization_id=NEW.organization_id
        AND e.target_type='FINANCE_AR_ISSUED_INVOICE'
        AND e.target_id=NEW.candidate_id
        AND e.storage_state='READY' AND u.state='READY'
        AND e.finalized_at IS NOT NULL AND u.finalized_at IS NOT NULL
        AND e.object_key_ref IS NOT NULL
        AND e.sha256=u.expected_sha256 AND e.size_bytes=u.expected_size_bytes
        AND e.content_type='application/pdf'
        AND e.classification_code='HIGHLY_RESTRICTED'
        AND e.client_visibility_mode='INTERNAL_ONLY')
  THEN
      RAISE EXCEPTION 'Canonical protected invoice PDF evidence required'
        USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END; $$;

CREATE TRIGGER finance_ar_issued_invoice_guard
BEFORE INSERT ON finance_ar_issued_invoice FOR EACH ROW
EXECUTE FUNCTION finance_ar_issued_invoice_guard();
CREATE TRIGGER finance_ar_issued_invoice_immutable
BEFORE UPDATE OR DELETE ON finance_ar_issued_invoice FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

-- This table records internal issuance ONLY when every gate passes.
-- No seeded numbering, taxes, e-invoicing platform submission, delivery,
-- receivable journal, credit note, collections or bank balance.
