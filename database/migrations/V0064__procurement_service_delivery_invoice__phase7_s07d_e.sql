-- Phase 7: service execution and invoice verification after approved internal order.
-- Provisional version V0064. Not a warehouse receipt, payable, bank instruction,
-- supplier email, actual payment or finance ledger posting.

-- One real restricted binary slot per service deliverable or invoice attachment.
CREATE TABLE procurement_service_document_slot (
  id uuid PRIMARY KEY,
  operation_id uuid NOT NULL UNIQUE,
  organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
  service_order_id uuid NOT NULL,
  document_kind varchar(24) NOT NULL
    CHECK(document_kind IN ('DELIVERY','INVOICE')),
  created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL,
  correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
  UNIQUE(id,organization_id),
  UNIQUE(id,service_order_id,document_kind),
  FOREIGN KEY(service_order_id,organization_id)
    REFERENCES procurement_service_order(id,organization_id) ON DELETE RESTRICT
);
CREATE INDEX idx_service_document_order ON procurement_service_document_slot
  (organization_id,service_order_id,document_kind);
CREATE TRIGGER service_document_slot_immutable
BEFORE UPDATE OR DELETE ON procurement_service_document_slot
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

CREATE UNIQUE INDEX uq_service_purchase_document_evidence_slot
  ON evidence(target_id) WHERE target_type='SERVICE_PURCHASE_DOCUMENT';

CREATE FUNCTION guard_service_purchase_document_evidence() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE slot procurement_service_document_slot%ROWTYPE;
BEGIN
  SELECT * INTO slot FROM procurement_service_document_slot WHERE id=NEW.target_id;
  IF slot.id IS NULL OR slot.organization_id<>NEW.organization_id
    OR NEW.target_type<>'SERVICE_PURCHASE_DOCUMENT'
    OR NEW.evidence_requirement_key<>
      CASE WHEN slot.document_kind='DELIVERY' THEN 'SERVICE_DELIVERY_BINARY'
           ELSE 'SERVICE_INVOICE_BINARY' END
    OR NEW.evidence_type_code<>
      CASE WHEN slot.document_kind='DELIVERY' THEN 'SERVICE_DELIVERY'
           ELSE 'SERVICE_INVOICE' END
    OR NEW.classification_code<>'HIGHLY_RESTRICTED'
    OR NEW.client_visibility_mode<>'INTERNAL_ONLY'
    OR NEW.work_order_id IS NOT NULL
    OR NEW.content_type NOT IN ('application/pdf','image/jpeg','image/png','image/webp')
  THEN
    RAISE EXCEPTION 'Service attachment must be scoped to a private delivery/invoice slot'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER service_purchase_document_evidence_guard
BEFORE INSERT ON evidence
FOR EACH ROW WHEN (NEW.target_type='SERVICE_PURCHASE_DOCUMENT')
EXECUTE FUNCTION guard_service_purchase_document_evidence();

-- A reviewer records what actually happened, including rework/rejection.
-- Each record is immutable and its Evidence must be a READY service deliverable.
CREATE TABLE procurement_service_acceptance (
  id uuid PRIMARY KEY,
  operation_id uuid NOT NULL UNIQUE,
  organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
  service_order_id uuid NOT NULL,
  document_slot_id uuid NOT NULL,
  document_kind varchar(24) NOT NULL DEFAULT 'DELIVERY' CHECK(document_kind='DELIVERY'),
  evidence_id uuid NOT NULL UNIQUE REFERENCES evidence(id) ON DELETE RESTRICT,
  evidence_sha256 char(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
  outcome varchar(24) NOT NULL CHECK(outcome IN ('ACCEPTED','NEEDS_REWORK','REJECTED')),
  review_notes varchar(1000) NOT NULL CHECK(length(btrim(review_notes)) BETWEEN 12 AND 1000),
  reviewed_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  reviewed_at timestamptz NOT NULL,
  correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
  UNIQUE(id,organization_id),
  FOREIGN KEY(service_order_id,organization_id)
    REFERENCES procurement_service_order(id,organization_id) ON DELETE RESTRICT,
  FOREIGN KEY(document_slot_id,service_order_id,document_kind)
    REFERENCES procurement_service_document_slot(id,service_order_id,document_kind)
    ON DELETE RESTRICT
);
CREATE UNIQUE INDEX uq_service_accepted_once
  ON procurement_service_acceptance(service_order_id) WHERE outcome='ACCEPTED';
CREATE INDEX idx_service_acceptance_order ON procurement_service_acceptance
  (service_order_id,reviewed_at DESC,id DESC);
CREATE TRIGGER procurement_service_acceptance_immutable
BEFORE UPDATE OR DELETE ON procurement_service_acceptance
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

-- Real supplier invoice, stored even when amount is wrong (mismatch surfaces).
CREATE TABLE procurement_service_invoice (
  id uuid PRIMARY KEY,
  operation_id uuid NOT NULL UNIQUE,
  organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
  service_order_id uuid NOT NULL,
  supplier_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
  document_slot_id uuid NOT NULL,
  document_kind varchar(24) NOT NULL DEFAULT 'INVOICE' CHECK(document_kind='INVOICE'),
  evidence_id uuid NOT NULL UNIQUE REFERENCES evidence(id) ON DELETE RESTRICT,
  evidence_sha256 char(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
  invoice_number varchar(96) NOT NULL CHECK(length(btrim(invoice_number)) BETWEEN 2 AND 96),
  invoice_date date NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  service_amount numeric(24,6) NOT NULL CHECK(service_amount>=0),
  tax_amount numeric(24,6) NOT NULL CHECK(tax_amount>=0),
  total_amount numeric(24,6) NOT NULL
    CHECK(total_amount=ROUND(service_amount+tax_amount,6)),
  captured_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  captured_at timestamptz NOT NULL,
  correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
  UNIQUE(id,organization_id),
  FOREIGN KEY(service_order_id,organization_id)
    REFERENCES procurement_service_order(id,organization_id) ON DELETE RESTRICT,
  FOREIGN KEY(document_slot_id,service_order_id,document_kind)
    REFERENCES procurement_service_document_slot(id,service_order_id,document_kind)
    ON DELETE RESTRICT
);
CREATE UNIQUE INDEX uq_service_supplier_invoice_number
  ON procurement_service_invoice(organization_id,supplier_organization_id,lower(btrim(invoice_number)));
CREATE INDEX idx_service_invoice_order ON procurement_service_invoice
  (service_order_id,captured_at DESC,id DESC);
CREATE TRIGGER procurement_service_invoice_immutable
BEFORE UPDATE OR DELETE ON procurement_service_invoice
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

-- No attachment reference, hash or "accepted" checkbox from a client is
-- sufficient without canonical READY, restricted, slot-scoped Evidence.
CREATE FUNCTION guard_service_purchase_verified_document() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE slot_kind varchar(24);
  expected_type varchar(40);
  expected_req varchar(40);
BEGIN
  IF TG_TABLE_NAME='procurement_service_acceptance' THEN
    slot_kind:='DELIVERY'; expected_type:='SERVICE_DELIVERY';
    expected_req:='SERVICE_DELIVERY_BINARY';
  ELSE
    slot_kind:='INVOICE'; expected_type:='SERVICE_INVOICE';
    expected_req:='SERVICE_INVOICE_BINARY';
  END IF;
  IF NOT EXISTS(
    SELECT 1 FROM procurement_service_document_slot slot
    JOIN procurement_service_order ord ON ord.id=slot.service_order_id
      AND ord.organization_id=slot.organization_id
    JOIN evidence e ON e.target_id=slot.id AND e.id=NEW.evidence_id
      AND e.organization_id=slot.organization_id
      AND e.target_type='SERVICE_PURCHASE_DOCUMENT'
    JOIN evidence_upload_session us ON us.evidence_id=e.id
    WHERE slot.id=NEW.document_slot_id
      AND slot.organization_id=NEW.organization_id
      AND slot.service_order_id=NEW.service_order_id
      AND slot.document_kind=slot_kind
      AND e.evidence_requirement_key=expected_req
      AND e.evidence_type_code=expected_type
      AND e.sha256=NEW.evidence_sha256
      AND us.expected_sha256=NEW.evidence_sha256
      AND e.storage_state='READY' AND us.state='READY'
      AND e.finalized_at IS NOT NULL AND us.finalized_at IS NOT NULL
      AND e.object_key_ref IS NOT NULL
      AND e.classification_code='HIGHLY_RESTRICTED'
      AND e.client_visibility_mode='INTERNAL_ONLY'
      AND ((e.content_type='application/pdf' AND e.security_scan_class='SIGNED_DOCUMENT')
       OR (e.content_type IN ('image/jpeg','image/png','image/webp')
              AND e.security_scan_class='NATIVE_MEDIA'))
  ) THEN
    RAISE EXCEPTION 'Service attachment must be a finalized private source document'
      USING ERRCODE='23514';
  END IF;
  IF TG_TABLE_NAME='procurement_service_invoice' AND
      NOT EXISTS(SELECT 1 FROM procurement_service_order o
        WHERE o.id=NEW.service_order_id AND o.organization_id=NEW.organization_id
          AND o.supplier_organization_id=NEW.supplier_organization_id)
  THEN
    RAISE EXCEPTION 'Invoice supplier does not match the approved service order'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER service_acceptance_evidence_guard
BEFORE INSERT ON procurement_service_acceptance
FOR EACH ROW EXECUTE FUNCTION guard_service_purchase_verified_document();
CREATE TRIGGER service_invoice_evidence_guard
BEFORE INSERT ON procurement_service_invoice
FOR EACH ROW EXECUTE FUNCTION guard_service_purchase_verified_document();

-- Append-only invoice check: compares approved order vs independently accepted
-- service and actual invoice, never a Warehouse stock receipt.
CREATE TABLE procurement_service_invoice_match (
  id uuid PRIMARY KEY,
  operation_id uuid NOT NULL UNIQUE,
  organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
  service_invoice_id uuid NOT NULL,
  service_order_id uuid NOT NULL,
  revision bigint NOT NULL CHECK(revision>=1),
  result_code varchar(32) NOT NULL CHECK(result_code IN (
    'MATCHED','NO_ACCEPTANCE','CURRENCY_MISMATCH','AMOUNT_MISMATCH',
    'DELIVERY_REVIEW_REQUIRED','SOURCE_CHANGED'
  )),
  accepted_evidence_id uuid REFERENCES evidence(id) ON DELETE RESTRICT,
  expected_currency char(3) NOT NULL,
  invoiced_currency char(3) NOT NULL,
  expected_total numeric(24,6) NOT NULL CHECK(expected_total>=0),
  invoiced_total numeric(24,6) NOT NULL CHECK(invoiced_total>=0),
  source_sha256 char(64) NOT NULL CHECK(source_sha256 ~ '^[0-9a-f]{64}$'),
  checked_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  checked_at timestamptz NOT NULL,
  correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
  UNIQUE(id,organization_id),
  UNIQUE(service_invoice_id,revision),
  FOREIGN KEY(service_invoice_id,organization_id)
    REFERENCES procurement_service_invoice(id,organization_id) ON DELETE RESTRICT,
  FOREIGN KEY(service_order_id,organization_id)
    REFERENCES procurement_service_order(id,organization_id) ON DELETE RESTRICT
);
CREATE INDEX idx_service_invoice_match_current
  ON procurement_service_invoice_match(service_invoice_id,revision DESC);
CREATE TRIGGER procurement_service_invoice_match_immutable
BEFORE UPDATE OR DELETE ON procurement_service_invoice_match
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

-- Eligibility marker for Finance 8A/B integration, NOT a payable or payment.
CREATE TABLE procurement_service_finance_handoff (
  id uuid PRIMARY KEY,
  operation_id uuid NOT NULL UNIQUE,
  organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
  service_invoice_id uuid NOT NULL UNIQUE,
  match_event_id uuid NOT NULL UNIQUE,
  reviewed_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  reviewed_at timestamptz NOT NULL,
  correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
  FOREIGN KEY(service_invoice_id,organization_id)
    REFERENCES procurement_service_invoice(id,organization_id) ON DELETE RESTRICT,
  FOREIGN KEY(match_event_id,organization_id)
    REFERENCES procurement_service_invoice_match(id,organization_id) ON DELETE RESTRICT
);
CREATE TRIGGER procurement_service_finance_handoff_immutable
BEFORE UPDATE OR DELETE ON procurement_service_finance_handoff
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

CREATE FUNCTION guard_procurement_service_finance_handoff() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS(
    SELECT 1 FROM procurement_service_invoice_match m
    JOIN procurement_service_invoice inv ON inv.id=m.service_invoice_id
    JOIN procurement_service_order ord ON ord.id=inv.service_order_id
    WHERE m.id=NEW.match_event_id AND m.organization_id=NEW.organization_id
      AND m.service_invoice_id=NEW.service_invoice_id
      AND inv.organization_id=NEW.organization_id
      AND ord.organization_id=NEW.organization_id
      AND m.service_order_id=ord.id AND m.result_code='MATCHED'
      AND m.revision=(SELECT MAX(revision) FROM procurement_service_invoice_match
                      WHERE service_invoice_id=inv.id)
      AND m.expected_total=ord.total_amount
      AND m.invoiced_total=inv.total_amount
      AND m.expected_currency=ord.currency
      AND m.invoiced_currency=inv.currency
      AND EXISTS(
        SELECT 1 FROM procurement_service_acceptance ac
        WHERE ac.service_order_id=ord.id AND ac.organization_id=NEW.organization_id
          AND ac.outcome='ACCEPTED' AND ac.evidence_id=m.accepted_evidence_id
          AND ac.id=(SELECT latest.id FROM procurement_service_acceptance latest
            WHERE latest.service_order_id=ord.id
            ORDER BY latest.reviewed_at DESC,latest.id DESC LIMIT 1))
  ) THEN
    RAISE EXCEPTION 'Finance handoff requires latest genuinely matched service proof'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER procurement_service_finance_handoff_guard
BEFORE INSERT ON procurement_service_finance_handoff
FOR EACH ROW EXECUTE FUNCTION guard_procurement_service_finance_handoff();
