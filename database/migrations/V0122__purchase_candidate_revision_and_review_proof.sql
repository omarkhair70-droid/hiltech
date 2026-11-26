-- A corrected purchasing proposal is a new immutable candidate. Existing
-- approvals and issued orders keep their original source identities.
ALTER TABLE procurement_po_candidate
    ADD COLUMN revision_number integer NOT NULL DEFAULT 1 CHECK (revision_number >= 1),
    ADD COLUMN replaces_candidate_id uuid NULL,
    ADD CONSTRAINT ck_procurement_candidate_revision_root CHECK (
        (revision_number=1 AND replaces_candidate_id IS NULL) OR
        (revision_number>1 AND replaces_candidate_id IS NOT NULL)
    );

DROP INDEX uq_procurement_candidate_initial_round;
CREATE UNIQUE INDEX uq_procurement_candidate_round_revision ON procurement_po_candidate(rfq_id,revision_number);
CREATE UNIQUE INDEX uq_procurement_candidate_round_root ON procurement_po_candidate(rfq_id) WHERE replaces_candidate_id IS NULL;
CREATE UNIQUE INDEX uq_procurement_candidate_successor ON procurement_po_candidate(replaces_candidate_id) WHERE replaces_candidate_id IS NOT NULL;
CREATE UNIQUE INDEX uq_procurement_candidate_revision_scope ON procurement_po_candidate(id,organization_id,rfq_id);
ALTER TABLE procurement_po_candidate ADD CONSTRAINT fk_procurement_candidate_predecessor
    FOREIGN KEY(replaces_candidate_id,organization_id,rfq_id)
    REFERENCES procurement_po_candidate(id,organization_id,rfq_id) ON DELETE RESTRICT;

CREATE FUNCTION validate_procurement_candidate_revision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    previous procurement_po_candidate%ROWTYPE;
BEGIN
    PERFORM id FROM procurement_supplier_rfq WHERE id=NEW.rfq_id AND organization_id=NEW.organization_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Candidate sourcing scope is unavailable' USING ERRCODE='23514'; END IF;
    SELECT * INTO previous FROM procurement_po_candidate WHERE rfq_id=NEW.rfq_id ORDER BY revision_number DESC LIMIT 1;
    IF NOT FOUND THEN
        IF NEW.revision_number<>1 OR NEW.replaces_candidate_id IS NOT NULL THEN
            RAISE EXCEPTION 'An initial candidate must start the revision chain' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.replaces_candidate_id IS DISTINCT FROM previous.id OR NEW.revision_number<>previous.revision_number+1 THEN
            RAISE EXCEPTION 'The candidate predecessor changed' USING ERRCODE='23514';
        END IF;
        IF EXISTS(SELECT 1 FROM procurement_purchase_order po JOIN procurement_po_candidate c ON c.id=po.candidate_id
                  WHERE c.rfq_id=NEW.rfq_id) THEN
            RAISE EXCEPTION 'An issued order requires its governed commercial correction workflow' USING ERRCODE='23514';
        END IF;
        IF EXISTS(SELECT 1 FROM approval_request a WHERE a.organization_id=NEW.organization_id
                  AND a.subject_type='PROCUREMENT_PO_CANDIDATE' AND a.subject_id=previous.id AND a.state='PENDING') THEN
            RAISE EXCEPTION 'End the pending review before replacing its source candidate' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER procurement_candidate_revision_chain BEFORE INSERT ON procurement_po_candidate
    FOR EACH ROW EXECUTE FUNCTION validate_procurement_candidate_revision();

-- Protected server delivery and the device's confirmation that its viewer
-- opened are separate facts. Neither claims that a human read the quotation.
CREATE TABLE procurement_po_review_quote_delivery (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    candidate_id uuid NOT NULL,
    approval_request_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
    approval_request_version bigint NOT NULL CHECK (approval_request_version>=1),
    policy_version integer NOT NULL CHECK (policy_version>=1),
    quote_evidence_id uuid NOT NULL REFERENCES procurement_supplier_quote_evidence(id) ON DELETE RESTRICT,
    evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
    source_sha256 char(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    reviewer_identity_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    delivered_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL,
    FOREIGN KEY(candidate_id,organization_id) REFERENCES procurement_po_candidate(id,organization_id) ON DELETE RESTRICT
);
CREATE INDEX idx_procurement_po_review_quote_delivery ON procurement_po_review_quote_delivery
    (candidate_id,approval_request_id,reviewer_identity_id,approval_request_version);

CREATE TABLE procurement_po_review_quote_opening (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    delivery_id uuid NOT NULL UNIQUE REFERENCES procurement_po_review_quote_delivery(id) ON DELETE RESTRICT,
    opened_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL
);
CREATE TRIGGER procurement_po_review_quote_delivery_immutable BEFORE UPDATE OR DELETE ON procurement_po_review_quote_delivery
    FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
CREATE TRIGGER procurement_po_review_quote_opening_immutable BEFORE UPDATE OR DELETE ON procurement_po_review_quote_opening
    FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
