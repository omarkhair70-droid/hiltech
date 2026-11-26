-- Phase 7 S03: internal PO issuance backed by a current canonical approval,
-- separate issuing authority, exact immutable candidate and scoped destination.
-- A supplier-facing transmission, warehouse receipt, payable or payment is NOT issued.
CREATE UNIQUE INDEX uq_po_candidate_id_org
    ON procurement_po_candidate(id, organization_id);
CREATE UNIQUE INDEX uq_po_approval_request_id_org
    ON approval_request(id, organization_id);
CREATE UNIQUE INDEX uq_po_issuer_binding_id_org
    ON approval_authority_binding(id, organization_id);
CREATE UNIQUE INDEX uq_po_storage_location_id_org
    ON storage_location(id, organization_id);

CREATE TABLE procurement_po_number_counter (
    organization_id uuid PRIMARY KEY REFERENCES organization(id) ON DELETE RESTRICT,
    last_number bigint NOT NULL CHECK (last_number >= 1)
);

CREATE TABLE procurement_purchase_order (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    candidate_id uuid NOT NULL UNIQUE,
    approval_request_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
    approval_policy_key varchar(96) NOT NULL,
    approval_policy_version integer NOT NULL CHECK (approval_policy_version >= 1),
    issuer_authority_key varchar(80) NOT NULL,
    issuer_authority_binding_id uuid NOT NULL REFERENCES approval_authority_binding(id) ON DELETE RESTRICT,
    po_code varchar(64) NOT NULL,
    po_serial bigint NOT NULL CHECK (po_serial >= 1),
    supplier_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    delivery_storage_location_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    quantity numeric(18,6) NOT NULL CHECK (quantity > 0),
    unit_code varchar(32) NOT NULL CHECK (length(btrim(unit_code)) > 0),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    unit_price numeric(18,6) NOT NULL CHECK (unit_price >= 0),
    tax_amount numeric(24,6) NOT NULL CHECK (tax_amount >= 0),
    total_amount numeric(24,6) NOT NULL CHECK (total_amount = ROUND(quantity * unit_price + tax_amount, 6)),
    quote_evidence_id uuid NOT NULL REFERENCES procurement_supplier_quote_evidence(id) ON DELETE RESTRICT,
    quote_evidence_sha256 char(64) NOT NULL CHECK (quote_evidence_sha256 ~ '^[0-9a-f]{64}$'),
    state varchar(24) NOT NULL DEFAULT 'ISSUED' CHECK (state = 'ISSUED'),
    supplier_dispatch_state varchar(24) NOT NULL DEFAULT 'NOT_SENT' CHECK (supplier_dispatch_state = 'NOT_SENT'),
    version bigint NOT NULL DEFAULT 1 CHECK (version = 1),
    issued_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    issued_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    CONSTRAINT uq_po_organization_code UNIQUE (organization_id, po_code),
    CONSTRAINT uq_po_organization_serial UNIQUE (organization_id, po_serial),
    CONSTRAINT fk_po_candidate_org FOREIGN KEY (candidate_id, organization_id)
       REFERENCES procurement_po_candidate(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_approval_request_org FOREIGN KEY (approval_request_id, organization_id)
       REFERENCES approval_request(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_issuer_binding_org FOREIGN KEY (issuer_authority_binding_id, organization_id)
       REFERENCES approval_authority_binding(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_location_org FOREIGN KEY (delivery_storage_location_id, organization_id)
       REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_stock_item_org FOREIGN KEY (stock_item_id, organization_id)
       REFERENCES stock_item(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_approval_policy FOREIGN KEY (organization_id, approval_policy_key, approval_policy_version)
       REFERENCES approval_policy_version(organization_id, policy_key, version_number) ON DELETE RESTRICT
);

CREATE INDEX idx_po_organization_issued
    ON procurement_purchase_order (organization_id, issued_at DESC, id DESC);
CREATE TRIGGER procurement_po_number_issue_immutable
BEFORE UPDATE OR DELETE ON procurement_purchase_order
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
-- PO counter is mutable by design, but it is not an order/payment ledger.
-- The command runtime wraps serial allocation and insert in one transaction.
