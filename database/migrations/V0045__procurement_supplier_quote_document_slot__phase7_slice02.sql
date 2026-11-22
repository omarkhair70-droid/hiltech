-- Phase 7 S02: one immutable quote document upload target per buyer command.
-- The slot binds the exact RFQ, purchasing organization and supplier before
-- evidence bytes can be associated. It never grants supplier award authority.
CREATE TABLE procurement_supplier_quote_document_slot (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    rfq_id uuid NOT NULL REFERENCES procurement_supplier_rfq(id) ON DELETE RESTRICT,
    supplier_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    CONSTRAINT ck_supplier_quote_document_slot_org_supplier
        CHECK (organization_id <> supplier_organization_id)
);
CREATE INDEX idx_supplier_quote_document_slot_scope
    ON procurement_supplier_quote_document_slot
        (organization_id, rfq_id, supplier_organization_id);
CREATE UNIQUE INDEX uq_supplier_quote_evidence_per_document_slot
    ON evidence (target_id)
    WHERE target_type = 'SUPPLIER_QUOTE_DOCUMENT';
CREATE TRIGGER procurement_supplier_quote_document_slot_immutable
BEFORE UPDATE OR DELETE ON procurement_supplier_quote_document_slot
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
