-- P11A staging migration. DevOps integration owner assigns a Flyway version
-- only after upstream Phase 7/8/9/10 migrations land. No production defaults.
-- Reuses project, work_order, work_document_revision, evidence and audit_event.

CREATE TABLE service_handover_package (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    UNIQUE(project_id),
    UNIQUE(id, organization_id, project_id)
);

CREATE TABLE service_handover_package_version (
    id uuid PRIMARY KEY,
    package_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    version_number integer NOT NULL CHECK (version_number > 0),
    state varchar(32) NOT NULL DEFAULT 'DRAFT'
        CHECK (state IN ('DRAFT', 'INTERNAL_REVIEW', 'INTERNAL_APPROVED',
                        'ISSUED', 'CLIENT_ACCEPTED', 'REJECTED')),
    created_operation_id uuid NOT NULL UNIQUE,
    project_version_at_snapshot bigint NOT NULL CHECK (project_version_at_snapshot > 0),
    source_digest char(64) NOT NULL CHECK (source_digest ~ '^[0-9a-f]{64}$'),
    blockers_json jsonb NOT NULL CHECK (jsonb_typeof(blockers_json) = 'array'),
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_service_handover_parent FOREIGN KEY
        (package_id, organization_id, project_id)
        REFERENCES service_handover_package(id, organization_id, project_id)
        ON DELETE RESTRICT,
    UNIQUE(package_id, version_number),
    UNIQUE(id, organization_id, project_id)
);

CREATE TABLE service_handover_package_item (
    id uuid PRIMARY KEY,
    package_version_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    document_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
    source_sha256 char(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fk_service_handover_version FOREIGN KEY
        (package_version_id, organization_id, project_id)
        REFERENCES service_handover_package_version(id, organization_id, project_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_service_handover_revision FOREIGN KEY
        (revision_id, document_id, work_order_id, organization_id)
        REFERENCES work_document_revision(id, document_id, work_order_id, organization_id)
        ON DELETE RESTRICT,
    UNIQUE(package_version_id, document_id)
);

CREATE INDEX idx_service_handover_version_project
    ON service_handover_package_version(project_id, version_number DESC);

-- Package rows and source links are snapshots: never mutate the accepted evidence
-- reference or rewrite old version contents. Lifecycle advancement needs a separate
-- authorized transition implementation and source policy; not enabled by this slice.
CREATE FUNCTION service_handover_no_snapshot_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Handover snapshot is immutable'
        USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER service_handover_version_immutable
BEFORE UPDATE OR DELETE ON service_handover_package_version
FOR EACH ROW EXECUTE FUNCTION service_handover_no_snapshot_mutation();
CREATE TRIGGER service_handover_item_immutable
BEFORE UPDATE OR DELETE ON service_handover_package_item
FOR EACH ROW EXECUTE FUNCTION service_handover_no_snapshot_mutation();

-- Validate cross-object project ownership and READY evidence at insert,
-- not just in the API. Old references may later be superseded but remain
-- an immutable historical snapshot.
CREATE FUNCTION service_handover_item_validate()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    source_project uuid;
    source_evidence evidence%ROWTYPE;
BEGIN
    SELECT project_id INTO source_project FROM work_order
     WHERE id = NEW.work_order_id;
    IF source_project IS DISTINCT FROM NEW.project_id THEN
        RAISE EXCEPTION 'Cross-project document in handover'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO source_evidence FROM evidence WHERE id = NEW.evidence_id;
    IF NOT FOUND OR source_evidence.organization_id <> NEW.organization_id
       OR source_evidence.work_order_id IS DISTINCT FROM NEW.work_order_id
       OR source_evidence.target_type <> 'WORK_ORDER'
       OR source_evidence.storage_state <> 'READY'
       OR source_evidence.sha256 <> NEW.source_sha256 THEN
        RAISE EXCEPTION 'Evidence must be READY in the same work and organization'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM work_document_revision r
        WHERE r.id = NEW.revision_id
          AND r.evidence_id = NEW.evidence_id
    ) THEN
        RAISE EXCEPTION 'Revision evidence mismatch'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER service_handover_item_source_gate
BEFORE INSERT ON service_handover_package_item
FOR EACH ROW EXECUTE FUNCTION service_handover_item_validate();
