-- Phase 7 S07C: internal approved service order. Never an external supplier
-- transmission or payment; version provisional, DevOps assigns final number.
CREATE TABLE procurement_service_order_candidate (
  id uuid PRIMARY KEY,
  operation_id uuid NOT NULL UNIQUE,
  organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
  rfq_id uuid NOT NULL,
  quote_id uuid NOT NULL,
  supplier_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
  currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  total_amount numeric(24,6) NOT NULL CHECK (total_amount>=0),
  service_specification varchar(2000) NOT NULL CHECK (length(btrim(service_specification)) BETWEEN 20 AND 2000),
  service_acceptance_criteria varchar(2000) NOT NULL CHECK (length(btrim(service_acceptance_criteria)) BETWEEN 20 AND 2000),
  created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL,
  correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
  version bigint NOT NULL DEFAULT 1 CHECK(version=1),
  UNIQUE(id,organization_id),
  UNIQUE(rfq_id),
  FOREIGN KEY(rfq_id,organization_id)
    REFERENCES procurement_service_rfq(id,organization_id) ON DELETE RESTRICT,
  FOREIGN KEY(quote_id,organization_id)
    REFERENCES procurement_service_supplier_quote(id,organization_id) ON DELETE RESTRICT
);
CREATE INDEX idx_service_order_candidate_org ON procurement_service_order_candidate
  (organization_id,created_at DESC,id DESC);
CREATE TRIGGER procurement_service_order_candidate_immutable
BEFORE UPDATE OR DELETE ON procurement_service_order_candidate
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

CREATE FUNCTION guard_procurement_service_order_candidate() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE q procurement_service_supplier_quote%ROWTYPE;
DECLARE src procurement_service_rfq%ROWTYPE;
DECLARE need procurement_service_need%ROWTYPE;
DECLARE last procurement_service_need_event%ROWTYPE;
DECLARE team_current bigint;
BEGIN
  SELECT * INTO q FROM procurement_service_supplier_quote WHERE id=NEW.quote_id;
  SELECT * INTO src FROM procurement_service_rfq WHERE id=NEW.rfq_id FOR UPDATE;
  SELECT * INTO need FROM procurement_service_need WHERE id=src.service_need_id FOR UPDATE;
  SELECT * INTO last FROM procurement_service_need_event
    WHERE need_id=need.id ORDER BY result_version DESC LIMIT 1;
  SELECT t.version INTO team_current FROM team t JOIN organization o
    ON o.id=t.organization_id
    WHERE t.id=need.team_id AND t.active=true AND o.status='ACTIVE'
      AND o.organization_type='HILTECH';
  IF q.id IS NULL OR src.id IS NULL OR need.id IS NULL
    OR q.rfq_id<>NEW.rfq_id OR q.organization_id<>NEW.organization_id
    OR src.organization_id<>NEW.organization_id
    OR need.organization_id<>NEW.organization_id
    OR q.supplier_organization_id<>NEW.supplier_organization_id
    OR q.currency<>NEW.currency OR q.total_amount<>NEW.total_amount
    OR src.specification<>NEW.service_specification
    OR src.acceptance_criteria<>NEW.service_acceptance_criteria
    OR team_current IS NULL OR team_current<>need.team_version
    OR last.id IS NULL OR last.to_state<>'READY_FOR_SOURCING'
    OR last.result_version<>src.service_need_version
    OR (q.valid_until IS NOT NULL AND q.valid_until<CURRENT_DATE)
    OR NOT EXISTS(
      SELECT 1 FROM organization s WHERE s.id=q.supplier_organization_id
        AND s.organization_type='SUPPLIER' AND s.status='ACTIVE')
    OR NOT EXISTS(
      SELECT 1 FROM procurement_supplier_link_event l
      WHERE l.organization_id=NEW.organization_id
        AND l.supplier_organization_id=NEW.supplier_organization_id
        AND l.state='ACTIVE'
        AND NOT EXISTS(
          SELECT 1 FROM procurement_supplier_link_event newer
          WHERE newer.organization_id=l.organization_id
            AND newer.supplier_organization_id=l.supplier_organization_id
            AND newer.revision_number>l.revision_number))
    OR NOT EXISTS(
      SELECT 1 FROM evidence e JOIN evidence_upload_session us ON us.evidence_id=e.id
      JOIN procurement_service_quote_document_slot slot ON slot.id=q.slot_id
      WHERE e.id=q.evidence_id AND e.organization_id=NEW.organization_id
        AND e.target_type='SERVICE_QUOTE_DOCUMENT' AND e.target_id=slot.id
        AND e.storage_state='READY' AND us.state='READY'
        AND e.sha256=q.sha256 AND us.expected_sha256=q.sha256
        AND e.classification_code='HIGHLY_RESTRICTED'
        AND e.client_visibility_mode='INTERNAL_ONLY'
    )
  THEN
    RAISE EXCEPTION 'Service order candidate must freeze a current, verified quote'
      USING ERRCODE='23514';
  END IF;
  IF EXISTS(
    SELECT 1 FROM procurement_service_supplier_quote newer
    WHERE newer.rfq_id=q.rfq_id AND newer.supplier_organization_id=q.supplier_organization_id
      AND newer.revision_number>q.revision_number
  ) THEN
    RAISE EXCEPTION 'Service quote has been superseded' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER procurement_service_order_candidate_guard
BEFORE INSERT ON procurement_service_order_candidate
FOR EACH ROW EXECUTE FUNCTION guard_procurement_service_order_candidate();

-- Internal order is created ONLY after an APPROVED ApprovalEngine decision
-- for the exact immutable SERVICE_ORDER_CANDIDATE version and current policy.
CREATE TABLE procurement_service_order (
  id uuid PRIMARY KEY,
  operation_id uuid NOT NULL UNIQUE,
  organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
  candidate_id uuid NOT NULL UNIQUE,
  quote_id uuid NOT NULL,
  supplier_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
  approval_request_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
  approval_policy_key varchar(96) NOT NULL,
  approval_policy_version integer NOT NULL,
  issuer_authority_binding_id uuid NOT NULL REFERENCES approval_authority_binding(id) ON DELETE RESTRICT,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  total_amount numeric(24,6) NOT NULL CHECK(total_amount>=0),
  issued_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  issued_at timestamptz NOT NULL,
  correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
  UNIQUE(id,organization_id),
  FOREIGN KEY(candidate_id,organization_id)
    REFERENCES procurement_service_order_candidate(id,organization_id) ON DELETE RESTRICT,
  FOREIGN KEY(quote_id,organization_id)
    REFERENCES procurement_service_supplier_quote(id,organization_id) ON DELETE RESTRICT,
  FOREIGN KEY(approval_request_id,organization_id)
    REFERENCES approval_request(id,organization_id) ON DELETE RESTRICT,
  FOREIGN KEY(issuer_authority_binding_id,organization_id)
    REFERENCES approval_authority_binding(id,organization_id) ON DELETE RESTRICT,
  FOREIGN KEY(organization_id,approval_policy_key,approval_policy_version)
    REFERENCES approval_policy_version(organization_id,policy_key,version_number) ON DELETE RESTRICT
);
CREATE INDEX idx_service_order_org ON procurement_service_order(organization_id,issued_at DESC,id DESC);
CREATE TRIGGER procurement_service_order_immutable
BEFORE UPDATE OR DELETE ON procurement_service_order
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

CREATE FUNCTION guard_procurement_service_order() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE candidate procurement_service_order_candidate%ROWTYPE;
DECLARE approval approval_request%ROWTYPE;
DECLARE policy approval_policy_version%ROWTYPE;
BEGIN
  SELECT * INTO candidate FROM procurement_service_order_candidate WHERE id=NEW.candidate_id FOR UPDATE;
  SELECT * INTO approval FROM approval_request WHERE id=NEW.approval_request_id FOR UPDATE;
  SELECT * INTO policy FROM approval_policy_version
    WHERE organization_id=NEW.organization_id AND policy_key=NEW.approval_policy_key
      AND version_number=NEW.approval_policy_version;
  IF candidate.id IS NULL OR candidate.organization_id<>NEW.organization_id
    OR candidate.quote_id<>NEW.quote_id
    OR candidate.supplier_organization_id<>NEW.supplier_organization_id
    OR candidate.currency<>NEW.currency
    OR candidate.total_amount<>NEW.total_amount
    OR approval.id IS NULL OR approval.organization_id<>NEW.organization_id
    OR approval.subject_type<>'SERVICE_ORDER_CANDIDATE'
    OR approval.subject_id<>NEW.candidate_id OR approval.subject_version<>1
    OR approval.policy_key<>NEW.approval_policy_key
    OR approval.policy_version<>NEW.approval_policy_version
    OR approval.state<>'APPROVED'
    OR policy.id IS NULL OR policy.lifecycle_state<>'ACTIVE'
    OR policy.approval_required<>true OR policy.step_mode<>'SINGLE'
    OR policy.effective_from>NEW.issued_at
    OR (policy.effective_to IS NOT NULL AND policy.effective_to<=NEW.issued_at)
    OR NOT EXISTS(
      SELECT 1 FROM approval_decision d
      WHERE d.approval_request_id=approval.id AND d.decision='APPROVE'
        AND d.subject_version=1
      GROUP BY d.approval_request_id HAVING COUNT(*)=1)
    OR NOT EXISTS(
      SELECT 1 FROM approval_authority_binding b
      WHERE b.id=NEW.issuer_authority_binding_id
        AND b.organization_id=NEW.organization_id AND b.active=true
        AND b.effective_from<=NEW.issued_at
        AND (b.effective_to IS NULL OR b.effective_to>NEW.issued_at))
  THEN
    RAISE EXCEPTION 'Service order issuance requires current canonical approval'
      USING ERRCODE='23514';
  END IF;
  -- Re-check frozen source and quote on issuing. The candidate insert trigger
  -- cannot be rerun, so reject if RFQ no longer current or supplier revoked.
  IF NOT EXISTS(
    SELECT 1 FROM procurement_service_rfq rfq
    JOIN procurement_service_need need ON need.id=rfq.service_need_id
    JOIN team t ON t.id=need.team_id AND t.active=true AND t.version=need.team_version
    JOIN organization o ON o.id=need.organization_id AND o.status='ACTIVE'
    JOIN LATERAL(
      SELECT e.result_version,e.to_state FROM procurement_service_need_event e
      WHERE e.need_id=need.id ORDER BY e.result_version DESC LIMIT 1
    ) last ON true
    JOIN procurement_service_supplier_quote q ON q.id=candidate.quote_id
      AND q.rfq_id=rfq.id
    WHERE rfq.id=candidate.rfq_id AND rfq.organization_id=NEW.organization_id
      AND last.to_state='READY_FOR_SOURCING'
      AND last.result_version=rfq.service_need_version
      AND (q.valid_until IS NULL OR q.valid_until>=CURRENT_DATE)
      AND NOT EXISTS (
        SELECT 1 FROM procurement_service_supplier_quote newer
        WHERE newer.rfq_id=q.rfq_id
          AND newer.supplier_organization_id=q.supplier_organization_id
          AND newer.revision_number>q.revision_number)
      AND EXISTS(
        SELECT 1 FROM organization s WHERE s.id=q.supplier_organization_id
          AND s.status='ACTIVE' AND s.organization_type='SUPPLIER'
          AND (SELECT state FROM procurement_supplier_link_event link
               WHERE link.organization_id=NEW.organization_id
                 AND link.supplier_organization_id=s.id
               ORDER BY link.revision_number DESC LIMIT 1)='ACTIVE')
  ) THEN
    RAISE EXCEPTION 'Service order source is stale or supplier revoked'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER procurement_service_order_guard
BEFORE INSERT ON procurement_service_order
FOR EACH ROW EXECUTE FUNCTION guard_procurement_service_order();
