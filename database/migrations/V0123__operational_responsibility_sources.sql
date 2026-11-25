-- Explicit company operating responsibilities. These grants never satisfy an
-- ApprovalEngine policy and never imply authority to delegate or approve.
CREATE TABLE operational_authority_binding (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    authority_key varchar(64) NOT NULL CHECK (authority_key IN (
        'PROCUREMENT_BUYER',
        'PROCUREMENT_TECHNICAL_REVIEW',
        'PROCUREMENT_NEED_REQUEST',
        'PROCUREMENT_NEED_REVIEW',
        'PROCUREMENT_ORDER_ISSUE',
        'PROCUREMENT_SERVICE_ACCEPT',
        'FINANCE_SUPPLIER_REVIEW',
        'FINANCE_EMPLOYEE_REVIEW',
        'FINANCE_COMMERCIAL_REVIEW',
        'FINANCE_PAYABLE_PREPARE',
        'FINANCE_PAYABLE_POST',
        'FINANCE_RECEIVABLE_PREPARE',
        'FINANCE_RECEIVABLE_EXECUTE',
        'FINANCE_EMPLOYEE_PREPARE',
        'FINANCE_EMPLOYEE_EXECUTE',
        'FINANCE_BANK_STATEMENT_PREPARE',
        'FINANCE_BANK_STATEMENT_IMPORT',
        'FINANCE_BANK_RECONCILIATION_PREPARE',
        'FINANCE_BANK_RECONCILIATION_FINALIZE',
        'FINANCE_CASH_PREPARE',
        'FINANCE_CASH_EXECUTE',
        'FINANCE_PERIOD_CLOSE_PREPARE',
        'FINANCE_PERIOD_CLOSE_EXECUTE',
        'FINANCE_REPORT_READ'
    )),
    principal_type varchar(8) NOT NULL CHECK (principal_type IN ('USER', 'TEAM')),
    principal_user_id uuid REFERENCES user_identity(id) ON DELETE RESTRICT,
    principal_team_id uuid REFERENCES team(id) ON DELETE RESTRICT,
    active boolean NOT NULL DEFAULT true,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (id, organization_id),
    CHECK ((principal_type = 'USER' AND principal_user_id IS NOT NULL AND principal_team_id IS NULL)
        OR (principal_type = 'TEAM' AND principal_team_id IS NOT NULL AND principal_user_id IS NULL)),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE UNIQUE INDEX uq_operational_authority_current_user
    ON operational_authority_binding(organization_id, authority_key, principal_user_id)
    WHERE active = true AND effective_to IS NULL AND principal_type = 'USER';
CREATE UNIQUE INDEX uq_operational_authority_current_team
    ON operational_authority_binding(organization_id, authority_key, principal_team_id)
    WHERE active = true AND effective_to IS NULL AND principal_type = 'TEAM';
CREATE INDEX idx_operational_authority_current
    ON operational_authority_binding(organization_id, authority_key, active, effective_from, effective_to);

-- Existing approved delegation ceilings are preserved exactly. This only adds
-- the closed key vocabulary; no identity receives a new grant in a migration.
ALTER TABLE responsibility_administration_binding
    DROP CONSTRAINT responsibility_administration_binding_authority_key_check;
ALTER TABLE responsibility_administration_binding
    ADD CONSTRAINT responsibility_administration_binding_authority_key_check CHECK (authority_key IN (
        'PEOPLE_ADMIN',
        'PROJECT_ADMIN',
        'SALES_VIEW',
        'SALES_WRITE',
        'SALES_COST_VIEW',
        'SALES_RELEASE',
        'SALES_AWARD',
        'PAYROLL_READ',
        'PAYROLL_PREPARE',
        'PAYROLL_REVIEW',
        'SUPPLIER_MASTER_DATA_ADMIN',
        'APPROVAL_POLICY_ADMIN',
        'FINANCE_PAYROLL_PREPARE',
        'FINANCE_PAYROLL_REVIEW',
        'PROCUREMENT_BUYER',
        'PROCUREMENT_TECHNICAL_REVIEW',
        'PROCUREMENT_NEED_REQUEST',
        'PROCUREMENT_NEED_REVIEW',
        'PROCUREMENT_ORDER_ISSUE',
        'PROCUREMENT_SERVICE_ACCEPT',
        'FINANCE_SUPPLIER_REVIEW',
        'FINANCE_EMPLOYEE_REVIEW',
        'FINANCE_COMMERCIAL_REVIEW',
        'FINANCE_PAYABLE_PREPARE',
        'FINANCE_PAYABLE_POST',
        'FINANCE_RECEIVABLE_PREPARE',
        'FINANCE_RECEIVABLE_EXECUTE',
        'FINANCE_EMPLOYEE_PREPARE',
        'FINANCE_EMPLOYEE_EXECUTE',
        'FINANCE_BANK_STATEMENT_PREPARE',
        'FINANCE_BANK_STATEMENT_IMPORT',
        'FINANCE_BANK_RECONCILIATION_PREPARE',
        'FINANCE_BANK_RECONCILIATION_FINALIZE',
        'FINANCE_CASH_PREPARE',
        'FINANCE_CASH_EXECUTE',
        'FINANCE_PERIOD_CLOSE_PREPARE',
        'FINANCE_PERIOD_CLOSE_EXECUTE',
        'FINANCE_REPORT_READ'
    ));

CREATE FUNCTION guard_operational_authority_history() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Operating authority history is retained' USING ERRCODE = '23514';
    END IF;
    IF OLD.active <> true OR NEW.active <> false OR NEW.effective_to IS NULL
        OR NEW.effective_to < OLD.effective_from OR NEW.version <> OLD.version + 1
        OR (to_jsonb(NEW) - ARRAY['active','effective_to','version'])
            IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['active','effective_to','version']) THEN
        RAISE EXCEPTION 'Operating authority may only be ended at its exact version' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER operational_authority_history
    BEFORE UPDATE OR DELETE ON operational_authority_binding
    FOR EACH ROW EXECUTE FUNCTION guard_operational_authority_history();

-- Historical order receipts retain their original approval-binding FK.
-- New receipts record exactly one real source; the alternative is explicit.
ALTER TABLE procurement_purchase_order
    ALTER COLUMN issuer_authority_binding_id DROP NOT NULL,
    ADD COLUMN issuer_operational_binding_id uuid,
    ADD CONSTRAINT fk_po_operational_issuer_org FOREIGN KEY (issuer_operational_binding_id, organization_id)
        REFERENCES operational_authority_binding(id, organization_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_po_exact_issuer_source CHECK
        ((issuer_authority_binding_id IS NOT NULL) <> (issuer_operational_binding_id IS NOT NULL));
ALTER TABLE procurement_service_order
    ALTER COLUMN issuer_authority_binding_id DROP NOT NULL,
    ADD COLUMN issuer_operational_binding_id uuid,
    ADD CONSTRAINT fk_service_order_operational_issuer_org FOREIGN KEY (issuer_operational_binding_id, organization_id)
        REFERENCES operational_authority_binding(id, organization_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_service_order_exact_issuer_source CHECK
        ((issuer_authority_binding_id IS NOT NULL) <> (issuer_operational_binding_id IS NOT NULL));

CREATE FUNCTION current_purchase_issuer_source(
    company_id uuid, actor_id uuid, legacy_binding_id uuid, operating_binding_id uuid,
    source_key varchar, as_of timestamptz
) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE source_type varchar; source_user uuid; source_team uuid;
BEGIN
    IF (legacy_binding_id IS NOT NULL) = (operating_binding_id IS NOT NULL) THEN RETURN false; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM organization o JOIN organization_membership m ON m.organization_id = o.id
        JOIN user_identity u ON u.id = m.user_identity_id
        WHERE o.id = company_id AND o.status = 'ACTIVE' AND o.organization_type = 'HILTECH'
          AND u.id = actor_id AND u.status = 'ACTIVE' AND m.state = 'ACTIVE'
          AND m.valid_from <= as_of AND (m.valid_until IS NULL OR m.valid_until > as_of)
    ) THEN RETURN false; END IF;
    IF operating_binding_id IS NOT NULL THEN
        SELECT b.principal_type, b.principal_user_id, b.principal_team_id
            INTO source_type, source_user, source_team
        FROM operational_authority_binding b WHERE b.id = operating_binding_id
          AND b.organization_id = company_id AND b.authority_key = 'PROCUREMENT_ORDER_ISSUE'
          AND b.authority_key = source_key AND b.active = true AND b.effective_from <= as_of
          AND (b.effective_to IS NULL OR b.effective_to > as_of) FOR SHARE;
    ELSE
        SELECT b.principal_type, b.principal_user_id, b.principal_team_id
            INTO source_type, source_user, source_team
        FROM approval_authority_binding b WHERE b.id = legacy_binding_id
          AND b.organization_id = company_id AND b.authority_key = source_key
          AND b.active = true AND b.effective_from <= as_of
          AND (b.effective_to IS NULL OR b.effective_to > as_of) FOR SHARE;
    END IF;
    IF NOT FOUND THEN RETURN false; END IF;
    RETURN (source_type = 'USER' AND source_user = actor_id) OR
        (source_type = 'TEAM' AND EXISTS (
          SELECT 1 FROM team t JOIN team_membership tm ON tm.team_id = t.id
          WHERE t.id = source_team AND t.organization_id = company_id AND t.active = true
            AND tm.user_identity_id = actor_id AND tm.valid_from <= as_of
            AND (tm.valid_until IS NULL OR tm.valid_until > as_of)
        ));
END;
$$;

CREATE FUNCTION guard_stock_purchase_issuer_source() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT current_purchase_issuer_source(NEW.organization_id, NEW.issued_by_user_id,
        NEW.issuer_authority_binding_id, NEW.issuer_operational_binding_id,
        NEW.issuer_authority_key, NEW.issued_at) THEN
        RAISE EXCEPTION 'Purchase order requires its current real issuer source' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER stock_purchase_issuer_source
    BEFORE INSERT ON procurement_purchase_order
    FOR EACH ROW EXECUTE FUNCTION guard_stock_purchase_issuer_source();

-- Retain the service order's exact source, evidence and policy guard while
-- allowing the explicit operational issuer source and excluding self-approval.
CREATE OR REPLACE FUNCTION guard_procurement_service_order() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE candidate procurement_service_order_candidate%ROWTYPE;
  approval approval_request%ROWTYPE;
  policy approval_policy_version%ROWTYPE;
BEGIN
  SELECT * INTO candidate FROM procurement_service_order_candidate WHERE id=NEW.candidate_id FOR UPDATE;
  -- Quote revisions are serialized by this same canonical sourcing RFQ lock.
  -- Direct SQL writers cannot bypass the concurrent-source check.
  PERFORM 1 FROM procurement_service_rfq
    WHERE id=NEW.rfq_id AND organization_id=NEW.organization_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Service order RFQ source missing' USING ERRCODE='23514';
  END IF;
  SELECT * INTO approval FROM approval_request WHERE id=NEW.approval_request_id FOR UPDATE;
  SELECT * INTO policy FROM approval_policy_version
    WHERE organization_id=NEW.organization_id AND policy_key=NEW.approval_policy_key
      AND version_number=NEW.approval_policy_version;
  IF candidate.id IS NULL OR candidate.organization_id<>NEW.organization_id
    OR candidate.quote_id<>NEW.quote_id OR candidate.rfq_id<>NEW.rfq_id
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
        AND d.actor_user_id<>approval.requester_user_id
        AND d.actor_user_id<>candidate.created_by_user_id
      GROUP BY d.approval_request_id HAVING COUNT(*)=1)
    OR NOT current_purchase_issuer_source(NEW.organization_id, NEW.issued_by_user_id,
      NEW.issuer_authority_binding_id, NEW.issuer_operational_binding_id,
      NEW.issuer_authority_key, NEW.issued_at)
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
    JOIN organization o ON o.id=need.organization_id
      AND o.status='ACTIVE' AND o.organization_type='HILTECH'
    JOIN LATERAL(
      SELECT e.result_version,e.to_state FROM procurement_service_need_event e
      WHERE e.need_id=need.id ORDER BY e.result_version DESC LIMIT 1
    ) last ON true
    JOIN procurement_service_supplier_quote q ON q.id=candidate.quote_id
      AND q.rfq_id=rfq.id AND q.organization_id=rfq.organization_id
    JOIN procurement_service_quote_document_slot slot ON slot.id=q.slot_id
      AND slot.organization_id=rfq.organization_id
      AND slot.rfq_id=rfq.id AND slot.supplier_organization_id=q.supplier_organization_id
    JOIN evidence e ON e.id=q.evidence_id AND e.organization_id=rfq.organization_id
      AND e.target_type='SERVICE_QUOTE_DOCUMENT' AND e.target_id=slot.id
    JOIN evidence_upload_session us ON us.evidence_id=e.id
    WHERE rfq.id=candidate.rfq_id AND rfq.organization_id=NEW.organization_id
      AND last.to_state='READY_FOR_SOURCING'
      AND last.result_version=rfq.service_need_version
      AND e.sha256=q.sha256 AND us.expected_sha256=q.sha256
      AND e.storage_state='READY' AND us.state='READY'
      AND e.finalized_at IS NOT NULL AND us.finalized_at IS NOT NULL
      AND e.classification_code='HIGHLY_RESTRICTED'
      AND e.client_visibility_mode='INTERNAL_ONLY'
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
