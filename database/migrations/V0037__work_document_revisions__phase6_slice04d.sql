-- Phase 6 Slice 04D — canonical Work document/drawing revisions.
-- A revision is immutable content. Approval is resolved through the generic ApprovalEngine.
-- No HILTECH-specific drawing code, revision naming, approver, or policy is seeded here.

CREATE TABLE work_document (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    work_order_id uuid NOT NULL,
    requirement_key varchar(64) NOT NULL
        CHECK (requirement_key ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    document_code varchar(96) NOT NULL
        CHECK (length(btrim(document_code)) BETWEEN 1 AND 96),
    title varchar(240) NOT NULL
        CHECK (length(btrim(title)) BETWEEN 1 AND 240),
    approval_policy_key varchar(96) NOT NULL
        CHECK (approval_policy_key ~ '^[A-Z][A-Z0-9_]{2,95}$'),
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CONSTRAINT fk_work_document_work
        FOREIGN KEY (work_order_id, organization_id)
        REFERENCES work_order(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT uq_work_document_key
        UNIQUE (work_order_id, requirement_key, document_code),
    CONSTRAINT uq_work_document_context
        UNIQUE (id, work_order_id, organization_id)
);

CREATE INDEX idx_work_document_work_requirement
    ON work_document (work_order_id, requirement_key, document_code);

CREATE TABLE work_document_revision (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    work_order_id uuid NOT NULL,
    document_id uuid NOT NULL,
    revision_sequence integer NOT NULL CHECK (revision_sequence >= 1),
    revision_label varchar(96) NOT NULL
        CHECK (length(btrim(revision_label)) BETWEEN 1 AND 96),
    evidence_id uuid NOT NULL UNIQUE REFERENCES evidence(id) ON DELETE RESTRICT,
    supersedes_revision_id uuid NULL REFERENCES work_document_revision(id) ON DELETE RESTRICT,
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version = 1),
    CONSTRAINT fk_work_document_revision_document
        FOREIGN KEY (document_id, work_order_id, organization_id)
        REFERENCES work_document(id, work_order_id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT uq_work_document_revision_label
        UNIQUE (document_id, revision_label),
    CONSTRAINT uq_work_document_revision_sequence
        UNIQUE (document_id, revision_sequence),
    CONSTRAINT uq_work_document_revision_context
        UNIQUE (id, document_id, work_order_id, organization_id),
    CHECK (supersedes_revision_id IS NULL OR supersedes_revision_id <> id)
);

CREATE UNIQUE INDEX uq_work_document_root_revision
    ON work_document_revision (document_id)
    WHERE supersedes_revision_id IS NULL;

CREATE UNIQUE INDEX uq_work_document_revision_successor
    ON work_document_revision (supersedes_revision_id)
    WHERE supersedes_revision_id IS NOT NULL;

CREATE INDEX idx_work_document_revision_current
    ON work_document_revision (document_id, revision_sequence DESC);

CREATE TABLE work_document_revision_release (
    revision_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    document_id uuid NOT NULL,
    policy_key varchar(96) NOT NULL
        CHECK (policy_key ~ '^[A-Z][A-Z0-9_]{2,95}$'),
    policy_version integer NOT NULL CHECK (policy_version >= 1),
    release_mode varchar(32) NOT NULL
        CHECK (release_mode IN ('NO_APPROVAL_REQUIRED', 'APPROVAL_REQUEST')),
    approval_request_id uuid NULL UNIQUE REFERENCES approval_request(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_work_document_release_revision
        FOREIGN KEY (revision_id, document_id, work_order_id, organization_id)
        REFERENCES work_document_revision(id, document_id, work_order_id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_document_release_policy
        FOREIGN KEY (organization_id, policy_key, policy_version)
        REFERENCES approval_policy_version(organization_id, policy_key, version_number) ON DELETE RESTRICT,
    CHECK (
        (release_mode = 'NO_APPROVAL_REQUIRED' AND approval_request_id IS NULL)
        OR
        (release_mode = 'APPROVAL_REQUEST' AND approval_request_id IS NOT NULL)
    )
);

CREATE FUNCTION validate_work_document_revision()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    predecessor work_document_revision%ROWTYPE;
    source_evidence evidence%ROWTYPE;
BEGIN
    SELECT * INTO source_evidence
      FROM evidence
     WHERE id = NEW.evidence_id;

    IF NOT FOUND
       OR source_evidence.organization_id <> NEW.organization_id
       OR source_evidence.work_order_id IS DISTINCT FROM NEW.work_order_id
       OR source_evidence.target_type <> 'WORK_ORDER'
       OR source_evidence.target_id <> NEW.work_order_id
       OR source_evidence.storage_state <> 'READY' THEN
        RAISE EXCEPTION 'Work document revision must reference READY Evidence from the same WorkOrder'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.revision_sequence = 1 THEN
        IF NEW.supersedes_revision_id IS NOT NULL THEN
            RAISE EXCEPTION 'First Work document revision cannot supersede another revision'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.supersedes_revision_id IS NULL THEN
        RAISE EXCEPTION 'Later Work document revision must supersede the previous revision'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO predecessor
      FROM work_document_revision
     WHERE id = NEW.supersedes_revision_id;

    IF NOT FOUND
       OR predecessor.document_id <> NEW.document_id
       OR predecessor.organization_id <> NEW.organization_id
       OR predecessor.work_order_id <> NEW.work_order_id
       OR predecessor.revision_sequence <> NEW.revision_sequence - 1 THEN
        RAISE EXCEPTION 'Work document revision chain is invalid'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_work_document_revision
BEFORE INSERT ON work_document_revision
FOR EACH ROW EXECUTE FUNCTION validate_work_document_revision();

CREATE FUNCTION validate_work_document_release()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    document_row work_document%ROWTYPE;
    revision_row work_document_revision%ROWTYPE;
    approval_row approval_request%ROWTYPE;
BEGIN
    SELECT * INTO revision_row
      FROM work_document_revision
     WHERE id = NEW.revision_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Work document release revision does not exist'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO document_row
      FROM work_document
     WHERE id = NEW.document_id;

    IF NOT FOUND
       OR document_row.id <> revision_row.document_id
       OR document_row.organization_id <> NEW.organization_id
       OR document_row.work_order_id <> NEW.work_order_id
       OR document_row.approval_policy_key <> NEW.policy_key THEN
        RAISE EXCEPTION 'Work document release does not match immutable document policy/context'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.release_mode = 'APPROVAL_REQUEST' THEN
        SELECT * INTO approval_row
          FROM approval_request
         WHERE id = NEW.approval_request_id;

        IF NOT FOUND
           OR approval_row.organization_id <> NEW.organization_id
           OR approval_row.subject_type <> 'WORK_DOCUMENT_REVISION'
           OR approval_row.subject_id <> NEW.revision_id
           OR approval_row.subject_version <> revision_row.version
           OR approval_row.policy_key <> NEW.policy_key
           OR approval_row.policy_version <> NEW.policy_version THEN
            RAISE EXCEPTION 'Work document release ApprovalRequest does not match revision/policy'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_work_document_release
BEFORE INSERT ON work_document_revision_release
FOR EACH ROW EXECUTE FUNCTION validate_work_document_release();

CREATE FUNCTION guard_work_document_revision_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Work document revisions/releases are immutable; create a new revision'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER work_document_identity_immutable
BEFORE UPDATE OR DELETE ON work_document
FOR EACH ROW EXECUTE FUNCTION guard_work_document_revision_immutable();

CREATE TRIGGER work_document_revision_immutable
BEFORE UPDATE OR DELETE ON work_document_revision
FOR EACH ROW EXECUTE FUNCTION guard_work_document_revision_immutable();

CREATE TRIGGER work_document_release_immutable
BEFORE UPDATE OR DELETE ON work_document_revision_release
FOR EACH ROW EXECUTE FUNCTION guard_work_document_revision_immutable();
