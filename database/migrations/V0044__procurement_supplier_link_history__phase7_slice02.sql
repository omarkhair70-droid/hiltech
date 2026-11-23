-- Phase 7 S02: buyer-organization-scoped supplier relationship source.
-- Global organization_type=SUPPLIER alone never grants sourcing eligibility.
-- No public write endpoint: a source-backed master-data authority is required
-- before creating or changing any of these relationships.
CREATE TABLE procurement_supplier_link_event (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    supplier_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    revision_number integer NOT NULL CHECK (revision_number >= 1),
    state varchar(16) NOT NULL CHECK (state IN ('ACTIVE', 'INACTIVE')),
    source_reference varchar(240) NOT NULL CHECK (length(btrim(source_reference)) >= 8),
    recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    recorded_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    CONSTRAINT ck_procurement_supplier_link_not_self
        CHECK (organization_id <> supplier_organization_id),
    CONSTRAINT uq_procurement_supplier_link_revision
        UNIQUE (organization_id, supplier_organization_id, revision_number)
);
CREATE INDEX idx_procurement_supplier_link_current
    ON procurement_supplier_link_event
        (organization_id, supplier_organization_id, revision_number DESC);
CREATE TRIGGER procurement_supplier_link_event_immutable
BEFORE UPDATE OR DELETE ON procurement_supplier_link_event
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
