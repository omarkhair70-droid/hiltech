-- Phase 7 S03: an internal, immutable buyer-selected candidate.
-- This is NOT an issued Purchase Order or an approved financial commitment.
-- It is the exact-source material for later canonical ApprovalEngine evaluation.
CREATE UNIQUE INDEX uq_procurement_supplier_quote_scoped_candidate_source
  ON procurement_supplier_quote_evidence
     (id, rfq_id, supplier_organization_id, revision_number);

CREATE TABLE procurement_po_candidate (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    rfq_id uuid NOT NULL,
    purchase_requirement_id uuid NOT NULL,
    requirement_version bigint NOT NULL CHECK (requirement_version >= 2),
    quote_evidence_id uuid NOT NULL,
    quote_revision integer NOT NULL CHECK (quote_revision >= 1),
    supplier_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    stock_item_id uuid NOT NULL,
    quantity numeric(18,6) NOT NULL CHECK (quantity > 0),
    unit_code varchar(32) NOT NULL CHECK (length(btrim(unit_code)) > 0),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    unit_price numeric(18,6) NOT NULL CHECK (unit_price >= 0),
    tax_amount numeric(24,6) NOT NULL CHECK (tax_amount >= 0),
    total_amount numeric(24,6) NOT NULL CHECK (total_amount = quantity * unit_price + tax_amount),
    quote_evidence_sha256 char(64) NOT NULL CHECK (quote_evidence_sha256 ~ '^[0-9a-f]{64}$'),
    rationale varchar(1000) NOT NULL CHECK (length(btrim(rationale)) BETWEEN 12 AND 1000),
    selected_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    state varchar(32) NOT NULL DEFAULT 'DRAFT_FOR_APPROVAL'
      CHECK (state = 'DRAFT_FOR_APPROVAL'),
    version bigint NOT NULL DEFAULT 1 CHECK (version = 1),
    CONSTRAINT fk_procurement_candidate_rfq_scope
      FOREIGN KEY (rfq_id, organization_id)
      REFERENCES procurement_supplier_rfq(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_procurement_candidate_quote_scope
      FOREIGN KEY (quote_evidence_id, rfq_id, supplier_organization_id, quote_revision)
      REFERENCES procurement_supplier_quote_evidence(id, rfq_id, supplier_organization_id, revision_number)
      ON DELETE RESTRICT,
    CONSTRAINT fk_procurement_candidate_requirement_scope
      FOREIGN KEY (purchase_requirement_id, organization_id)
      REFERENCES procurement_purchase_requirement(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_procurement_candidate_item_scope
      FOREIGN KEY (stock_item_id, organization_id)
      REFERENCES stock_item(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT ck_procurement_candidate_separate_supplier
      CHECK (supplier_organization_id <> organization_id)
);

-- Only one buyer submission can be the initial approval candidate per sourcing
-- round. If later superseded, an explicit versioned proposal lifecycle will be
-- required; never silently overwrite this historical snapshot.
CREATE UNIQUE INDEX uq_procurement_candidate_initial_round
  ON procurement_po_candidate(rfq_id);
CREATE INDEX idx_procurement_candidate_org
  ON procurement_po_candidate(organization_id, created_at DESC);
CREATE TRIGGER procurement_po_candidate_immutable
BEFORE UPDATE OR DELETE ON procurement_po_candidate
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
