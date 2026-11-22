-- Phase 7 S07B: non-stock service sourcing, not a Warehouse RFQ or StockItem.
-- V0062 is provisional and must be allocated by DevOps before merge.
CREATE TABLE procurement_service_rfq (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    service_need_id uuid NOT NULL,
    service_need_version bigint NOT NULL CHECK (service_need_version>=2),
    specification varchar(2000) NOT NULL CHECK (length(btrim(specification)) BETWEEN 20 AND 2000),
    acceptance_criteria varchar(2000) NOT NULL CHECK (length(btrim(acceptance_criteria)) BETWEEN 20 AND 2000),
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id))>0),
    UNIQUE(id,organization_id),
    UNIQUE(service_need_id,service_need_version),
    FOREIGN KEY(service_need_id,organization_id)
      REFERENCES procurement_service_need(id,organization_id) ON DELETE RESTRICT
);
CREATE INDEX idx_service_rfq_need ON procurement_service_rfq(organization_id,service_need_id);
CREATE TRIGGER procurement_service_rfq_immutable
BEFORE UPDATE OR DELETE ON procurement_service_rfq
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

-- An RFQ may only be opened against a current, technical-ready service version.
CREATE FUNCTION guard_procurement_service_rfq() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  root procurement_service_need%ROWTYPE;
  latest procurement_service_need_event%ROWTYPE;
BEGIN
  SELECT * INTO root FROM procurement_service_need WHERE id=NEW.service_need_id FOR UPDATE;
  SELECT * INTO latest FROM procurement_service_need_event
    WHERE need_id=NEW.service_need_id ORDER BY result_version DESC LIMIT 1;
  IF root.id IS NULL OR root.organization_id<>NEW.organization_id
    OR latest.id IS NULL OR latest.result_version<>NEW.service_need_version
    OR latest.to_state<>'READY_FOR_SOURCING'
    OR NOT EXISTS (
      SELECT 1 FROM team t JOIN organization o ON o.id=t.organization_id
      WHERE t.id=root.team_id AND t.organization_id=root.organization_id
        AND t.version=root.team_version AND t.active=true
        AND o.status='ACTIVE' AND o.organization_type='HILTECH'
    )
  THEN
    RAISE EXCEPTION 'Service RFQ requires current independently reviewed source'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER procurement_service_rfq_guard
BEFORE INSERT ON procurement_service_rfq
FOR EACH ROW EXECUTE FUNCTION guard_procurement_service_rfq();

-- Supplier-specific immutable document slot, enabling the canonical private
-- READY Evidence flow. No externally supplied unverified URL is accepted.
CREATE TABLE procurement_service_quote_document_slot (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    rfq_id uuid NOT NULL,
    supplier_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id))>0),
    UNIQUE(id,organization_id),
    UNIQUE(id,rfq_id,supplier_organization_id),
    FOREIGN KEY(rfq_id,organization_id)
      REFERENCES procurement_service_rfq(id,organization_id) ON DELETE RESTRICT,
    CHECK (supplier_organization_id<>organization_id)
);
CREATE INDEX idx_service_quote_slot_source
  ON procurement_service_quote_document_slot
  (organization_id,rfq_id,supplier_organization_id);
CREATE TRIGGER procurement_service_quote_slot_immutable
BEFORE UPDATE OR DELETE ON procurement_service_quote_document_slot
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

CREATE FUNCTION guard_procurement_service_quote_slot() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM organization s
    WHERE s.id=NEW.supplier_organization_id AND s.status='ACTIVE'
      AND s.organization_type='SUPPLIER'
      AND (SELECT l.state FROM procurement_supplier_link_event l
        WHERE l.organization_id=NEW.organization_id
          AND l.supplier_organization_id=NEW.supplier_organization_id
        ORDER BY l.revision_number DESC LIMIT 1)='ACTIVE'
  ) THEN
    RAISE EXCEPTION 'Service quote supplier not currently linked and active'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER procurement_service_quote_slot_guard
BEFORE INSERT ON procurement_service_quote_document_slot
FOR EACH ROW EXECUTE FUNCTION guard_procurement_service_quote_slot();

-- Immutably store the supplier's verified actual quote, never grant an award.
CREATE TABLE procurement_service_supplier_quote (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    rfq_id uuid NOT NULL,
    slot_id uuid NOT NULL,
    supplier_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    evidence_id uuid NOT NULL UNIQUE REFERENCES evidence(id) ON DELETE RESTRICT,
    sha256 char(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
    supplier_quote_reference varchar(120) NOT NULL CHECK(length(btrim(supplier_quote_reference)) BETWEEN 2 AND 120),
    revision_number integer NOT NULL CHECK(revision_number>=1),
    currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
    service_amount numeric(24,6) NOT NULL CHECK(service_amount>=0),
    tax_amount numeric(24,6) NOT NULL CHECK(tax_amount>=0),
    total_amount numeric(24,6) NOT NULL CHECK(total_amount=ROUND(service_amount+tax_amount,6)),
    valid_until date,
    service_terms varchar(1000) NOT NULL CHECK(length(btrim(service_terms)) BETWEEN 12 AND 1000),
    recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    recorded_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
    UNIQUE(id,organization_id),
    UNIQUE(rfq_id,supplier_organization_id,revision_number),
    FOREIGN KEY(rfq_id,organization_id)
      REFERENCES procurement_service_rfq(id,organization_id) ON DELETE RESTRICT,
    FOREIGN KEY(slot_id,rfq_id,supplier_organization_id)
      REFERENCES procurement_service_quote_document_slot(id,rfq_id,supplier_organization_id)
      ON DELETE RESTRICT
);
CREATE INDEX idx_service_supplier_quote_rfq
  ON procurement_service_supplier_quote(rfq_id,supplier_organization_id,revision_number DESC);
CREATE TRIGGER procurement_service_supplier_quote_immutable
BEFORE UPDATE OR DELETE ON procurement_service_supplier_quote
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

CREATE FUNCTION guard_procurement_service_quote_record() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM procurement_service_quote_document_slot slot
    JOIN evidence e ON e.target_id=slot.id
      AND e.target_type='SERVICE_QUOTE_DOCUMENT'
    JOIN evidence_upload_session us ON us.evidence_id=e.id
    WHERE slot.id=NEW.slot_id AND slot.organization_id=NEW.organization_id
      AND slot.rfq_id=NEW.rfq_id
      AND slot.supplier_organization_id=NEW.supplier_organization_id
      AND e.id=NEW.evidence_id AND e.organization_id=NEW.organization_id
      AND e.sha256=NEW.sha256 AND us.expected_sha256=NEW.sha256
      AND e.storage_state='READY' AND us.state='READY'
      AND e.finalized_at IS NOT NULL AND us.finalized_at IS NOT NULL
      AND e.object_key_ref IS NOT NULL
      AND e.classification_code='HIGHLY_RESTRICTED'
      AND e.client_visibility_mode='INTERNAL_ONLY'
      AND e.evidence_requirement_key='SERVICE_QUOTE_BINARY'
      AND e.evidence_type_code='SERVICE_QUOTE'
      AND ((e.content_type='application/pdf' AND e.security_scan_class='SIGNED_DOCUMENT')
        OR (e.content_type IN ('image/jpeg','image/png','image/webp')
              AND e.security_scan_class='NATIVE_MEDIA'))
  ) THEN
    RAISE EXCEPTION 'Service quote requires an actual private READY binary upload'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER procurement_service_supplier_quote_guard
BEFORE INSERT ON procurement_service_supplier_quote
FOR EACH ROW EXECUTE FUNCTION guard_procurement_service_quote_record();


-- No unrelated WorkOrder, HR or stock quote evidence can satisfy this slot.
CREATE UNIQUE INDEX uq_service_quote_document_evidence_slot
  ON evidence(target_id) WHERE target_type='SERVICE_QUOTE_DOCUMENT';

CREATE FUNCTION guard_service_quote_evidence_insert() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  slot procurement_service_quote_document_slot%ROWTYPE;
  rfq procurement_service_rfq%ROWTYPE;
BEGIN
  IF NEW.target_type<>'SERVICE_QUOTE_DOCUMENT' THEN RETURN NEW; END IF;
  SELECT * INTO slot FROM procurement_service_quote_document_slot
    WHERE id=NEW.target_id;
  SELECT * INTO rfq FROM procurement_service_rfq WHERE id=slot.rfq_id;
  IF slot.id IS NULL OR rfq.id IS NULL
    OR NEW.organization_id<>slot.organization_id
    OR rfq.organization_id<>slot.organization_id
    OR NEW.evidence_requirement_key<>'SERVICE_QUOTE_BINARY'
    OR NEW.evidence_type_code<>'SERVICE_QUOTE'
    OR NEW.classification_code<>'HIGHLY_RESTRICTED'
    OR NEW.client_visibility_mode<>'INTERNAL_ONLY'
    OR NEW.work_order_id IS NOT NULL
    OR (NEW.content_type<>'application/pdf'
        AND NEW.content_type NOT IN ('image/jpeg','image/png','image/webp'))
  THEN
    RAISE EXCEPTION 'Service supplier quote needs scoped private binary Evidence'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER service_quote_evidence_insert_guard
BEFORE INSERT ON evidence
FOR EACH ROW WHEN (NEW.target_type='SERVICE_QUOTE_DOCUMENT')
EXECUTE FUNCTION guard_service_quote_evidence_insert();
