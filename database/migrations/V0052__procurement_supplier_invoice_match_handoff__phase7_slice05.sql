-- Phase 7 / Slice 05: private invoice document slot, immutable invoice
-- and versioned three-way-match evidence. No payable or payment is created.
CREATE TABLE procurement_supplier_invoice_document_slot (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    purchase_order_id uuid NOT NULL,
    supplier_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    UNIQUE(id, organization_id),
    FOREIGN KEY(purchase_order_id, organization_id)
      REFERENCES procurement_purchase_order(id, organization_id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX uq_supplier_invoice_document_evidence_slot
  ON evidence(target_id) WHERE target_type='SUPPLIER_INVOICE_DOCUMENT';
CREATE TRIGGER procurement_supplier_invoice_slot_immutable
BEFORE UPDATE OR DELETE ON procurement_supplier_invoice_document_slot
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

CREATE TABLE procurement_supplier_invoice_intake (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    purchase_order_id uuid NOT NULL,
    supplier_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    document_slot_id uuid NOT NULL UNIQUE,
    evidence_id uuid NOT NULL UNIQUE REFERENCES evidence(id) ON DELETE RESTRICT,
    invoice_number varchar(96) NOT NULL CHECK(length(btrim(invoice_number)) BETWEEN 2 AND 96),
    invoice_date date NOT NULL,
    currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
    quantity numeric(18,6) NOT NULL CHECK(quantity > 0),
    unit_price numeric(18,6) NOT NULL CHECK(unit_price >= 0),
    tax_amount numeric(24,6) NOT NULL CHECK(tax_amount >= 0),
    total_amount numeric(24,6) NOT NULL
      CHECK(total_amount=ROUND(quantity*unit_price+tax_amount,6)),
    submitted_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    submitted_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    UNIQUE(id, organization_id),
    UNIQUE(organization_id, supplier_organization_id, invoice_number),
    FOREIGN KEY (purchase_order_id, organization_id)
      REFERENCES procurement_purchase_order(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (document_slot_id, organization_id)
      REFERENCES procurement_supplier_invoice_document_slot(id, organization_id) ON DELETE RESTRICT
);
CREATE INDEX idx_procurement_invoice_po ON procurement_supplier_invoice_intake
  (purchase_order_id, submitted_at DESC);

CREATE FUNCTION guard_procurement_supplier_invoice_intake_insert()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    slot procurement_supplier_invoice_document_slot%ROWTYPE;
    po procurement_purchase_order%ROWTYPE;
BEGIN
    SELECT * INTO slot FROM procurement_supplier_invoice_document_slot
      WHERE id=NEW.document_slot_id;
    SELECT * INTO po FROM procurement_purchase_order
      WHERE id=NEW.purchase_order_id FOR UPDATE;
    IF slot.id IS NULL OR po.id IS NULL OR
       slot.organization_id <> NEW.organization_id OR
       slot.purchase_order_id <> NEW.purchase_order_id OR
       slot.supplier_organization_id <> NEW.supplier_organization_id OR
       po.organization_id <> NEW.organization_id OR
       po.supplier_organization_id <> NEW.supplier_organization_id OR
       NOT EXISTS (
         SELECT 1 FROM evidence e
         WHERE e.id=NEW.evidence_id
           AND e.organization_id=NEW.organization_id
           AND e.target_type='SUPPLIER_INVOICE_DOCUMENT'
           AND e.target_id=NEW.document_slot_id
           AND e.storage_state='READY'
       ) THEN
       RAISE EXCEPTION 'Supplier invoice requires exact trusted READY Evidence and PO source'
         USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER procurement_supplier_invoice_intake_insert_guard
BEFORE INSERT ON procurement_supplier_invoice_intake
FOR EACH ROW EXECUTE FUNCTION guard_procurement_supplier_invoice_intake_insert();
CREATE TRIGGER procurement_supplier_invoice_intake_immutable
BEFORE UPDATE OR DELETE ON procurement_supplier_invoice_intake
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

CREATE TABLE procurement_supplier_invoice_match_event (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    invoice_id uuid NOT NULL,
    purchase_order_id uuid NOT NULL,
    revision bigint NOT NULL CHECK(revision>=1),
    result_code varchar(32) NOT NULL CHECK(result_code IN (
      'MATCHED','PARTIAL_RECEIPT','QUANTITY_MISMATCH','PRICE_MISMATCH',
      'TAX_MISMATCH','CURRENCY_MISMATCH','MISSING_RECEIPT','REVIEW_REQUIRED'
    )),
    po_quantity numeric(18,6) NOT NULL,
    received_quantity numeric(18,6) NOT NULL,
    invoiced_quantity numeric(18,6) NOT NULL,
    quantity_variance numeric(18,6) NOT NULL,
    price_variance numeric(18,6) NOT NULL,
    tax_variance numeric(24,6) NOT NULL,
    source_sha256 char(64) NOT NULL CHECK(source_sha256 ~ '^[0-9a-f]{64}$'),
    checked_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    checked_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
    UNIQUE(invoice_id, revision),
    UNIQUE(invoice_id, source_sha256),
    FOREIGN KEY(invoice_id, organization_id)
      REFERENCES procurement_supplier_invoice_intake(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY(purchase_order_id, organization_id)
      REFERENCES procurement_purchase_order(id, organization_id) ON DELETE RESTRICT
);
CREATE INDEX idx_procurement_invoice_match_current
  ON procurement_supplier_invoice_match_event (invoice_id, revision DESC);
CREATE TRIGGER procurement_supplier_invoice_match_immutable
BEFORE UPDATE OR DELETE ON procurement_supplier_invoice_match_event
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

CREATE TABLE procurement_supplier_invoice_finance_handoff (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    invoice_id uuid NOT NULL UNIQUE,
    matched_event_id uuid NOT NULL UNIQUE REFERENCES procurement_supplier_invoice_match_event(id) ON DELETE RESTRICT,
    reviewed_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    reviewed_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
    FOREIGN KEY(invoice_id, organization_id)
      REFERENCES procurement_supplier_invoice_intake(id, organization_id) ON DELETE RESTRICT
);
CREATE TRIGGER procurement_supplier_invoice_finance_handoff_immutable
BEFORE UPDATE OR DELETE ON procurement_supplier_invoice_finance_handoff
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
