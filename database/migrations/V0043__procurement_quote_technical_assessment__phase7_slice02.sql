-- Immutable, versioned technical judgment on one exact supplier quote revision.
-- This is not purchase approval, supplier selection, or an edit to a quote.
ALTER TABLE procurement_supplier_quote_evidence
    ADD CONSTRAINT uq_procurement_quote_id_rfq_org
    UNIQUE (id, rfq_id, organization_id);

CREATE TABLE procurement_supplier_quote_technical_assessment (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    quote_evidence_id uuid NOT NULL,
    rfq_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    review_revision integer NOT NULL CHECK (review_revision >= 1),
    decision varchar(24) NOT NULL
        CHECK (decision IN ('COMPLIANT', 'NON_COMPLIANT', 'NEEDS_CLARIFICATION')),
    justification varchar(1000) NOT NULL
        CHECK (length(btrim(justification)) >= 8),
    reviewed_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    reviewed_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    CONSTRAINT fk_procurement_technical_assessment_quote_source
        FOREIGN KEY (quote_evidence_id, rfq_id, organization_id)
        REFERENCES procurement_supplier_quote_evidence(id, rfq_id, organization_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_procurement_quote_technical_review_revision
        UNIQUE (quote_evidence_id, review_revision)
);

CREATE INDEX idx_procurement_quote_technical_latest
    ON procurement_supplier_quote_technical_assessment
        (rfq_id, quote_evidence_id, review_revision DESC);

CREATE TRIGGER procurement_supplier_quote_technical_assessment_immutable
BEFORE UPDATE OR DELETE ON procurement_supplier_quote_technical_assessment
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
