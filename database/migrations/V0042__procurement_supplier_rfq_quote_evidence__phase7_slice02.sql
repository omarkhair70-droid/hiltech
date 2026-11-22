-- Phase 7 S02: one internal sourcing round per frozen technical requirement version.
-- Storing a quote is NOT issuing a purchase order, awarding a vendor or paying.
CREATE TABLE procurement_supplier_rfq (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    requirement_id uuid NOT NULL,
    requirement_version bigint NOT NULL CHECK (requirement_version >= 2),
    stock_item_id uuid NOT NULL,
    source_quantity numeric(18,6) NOT NULL CHECK (source_quantity > 0),
    source_unit_code varchar(32) NOT NULL CHECK (length(btrim(source_unit_code)) > 0),
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    state varchar(24) NOT NULL DEFAULT 'DRAFT' CHECK (state = 'DRAFT'),
    version bigint NOT NULL DEFAULT 1 CHECK (version = 1),
    CONSTRAINT fk_procurement_rfq_requirement_org
        FOREIGN KEY (requirement_id, organization_id)
        REFERENCES procurement_purchase_requirement(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_procurement_rfq_item_org
        FOREIGN KEY (stock_item_id, organization_id)
        REFERENCES stock_item(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT uq_procurement_rfq_source_version UNIQUE (requirement_id, requirement_version),
    CONSTRAINT uq_procurement_rfq_id_org UNIQUE (id, organization_id)
);

-- The latest revision for each supplier is determined by ordered, immutable
-- rows, never by overwriting a quote. No auto-award or multi-currency ranking.
CREATE TABLE procurement_supplier_quote_evidence (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    rfq_id uuid NOT NULL,
    supplier_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    supplier_quote_reference varchar(120) NOT NULL
        CHECK (length(btrim(supplier_quote_reference)) > 0),
    revision_number integer NOT NULL CHECK (revision_number >= 1),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    quoted_quantity numeric(18,6) NOT NULL CHECK (quoted_quantity > 0),
    quoted_unit_code varchar(32) NOT NULL CHECK (length(btrim(quoted_unit_code)) > 0),
    unit_price numeric(18,6) NOT NULL CHECK (unit_price >= 0),
    tax_amount numeric(24,6) NOT NULL CHECK (tax_amount >= 0),
    delivery_days integer CHECK (delivery_days IS NULL OR delivery_days >= 0),
    valid_until date,
    delivery_terms varchar(500),
    payment_terms varchar(500),
    warranty_terms varchar(500),
    source_evidence_ref varchar(240) NOT NULL CHECK (length(btrim(source_evidence_ref)) > 0),
    source_sha256 char(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    technical_compliance varchar(24) NOT NULL DEFAULT 'NOT_ASSESSED'
        CHECK (technical_compliance = 'NOT_ASSESSED'),
    recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    recorded_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    CONSTRAINT fk_procurement_quote_rfq_org
        FOREIGN KEY (rfq_id, organization_id)
        REFERENCES procurement_supplier_rfq(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT uq_procurement_quote_supplier_revision
        UNIQUE (rfq_id, supplier_organization_id, revision_number)
);

CREATE INDEX idx_procurement_supplier_quote_rfq
    ON procurement_supplier_quote_evidence (rfq_id, supplier_organization_id, revision_number DESC);
CREATE INDEX idx_procurement_supplier_rfq_org
    ON procurement_supplier_rfq (organization_id, requirement_id);

-- Never allow silent editing, deletion or replacement of submitted evidence.
CREATE TRIGGER procurement_supplier_rfq_immutable
BEFORE UPDATE OR DELETE ON procurement_supplier_rfq
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
CREATE TRIGGER procurement_supplier_quote_evidence_immutable
BEFORE UPDATE OR DELETE ON procurement_supplier_quote_evidence
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
